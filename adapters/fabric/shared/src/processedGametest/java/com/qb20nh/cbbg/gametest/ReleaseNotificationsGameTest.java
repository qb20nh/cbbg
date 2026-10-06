package com.qb20nh.cbbg.gametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.appender.AppenderLoggingException;
import org.apache.logging.log4j.core.config.Property;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** Exercises packaged generation notifications in the real chat and toast managers. */
@NullMarked
public final class ReleaseNotificationsGameTest implements FabricClientGameTest {
  private static final int WAIT_TICKS = 600;
  private static final Pattern CHAT_PATCHES_COUNT = Pattern.compile(" \\(x([1-9][0-9]*)\\)$");

  @Override
  public void runTest(ClientGameTestContext context) {
    ReleaseGraphics.check(context);
    JsonObject original = settings().deepCopy();
    FabricClientCommandSource source = ReleaseGenerationGameTest.silentSource();
    try (var world = context.worldBuilder().create()) {
      ReleaseViewport.waitForChunks(world);
      CommandDispatcher<FabricClientCommandSource> dispatcher =
          Objects.requireNonNull(
              context.computeOnClient(client -> ReleaseCommands.getActiveDispatcher()));
      try {
        context.runOnClient(
            client -> {
              command(dispatcher, source, "mode set enabled");
              command(dispatcher, source, "notification chat true");
              command(dispatcher, source, "notification toast true");
              clear(client);
            });
        CompletableFuture<?> replacement =
            context.computeOnClient(
                client -> {
                  configure(dispatcher, source, 256, 128, 913735);
                  command(dispatcher, source, "stbn generate");
                  CompletableFuture<?> old = ReleaseGenerationGameTest.pending();
                  if (old.isDone()) {
                    throw new AssertionError("Notification cancellation request was not in flight");
                  }
                  checkMessages(client, 1, 0);
                  checkToast(client, "generating");
                  configure(dispatcher, source, 16, 8, 74123);
                  command(dispatcher, source, "stbn generate");
                  CompletableFuture<?> next = ReleaseGenerationGameTest.pending();
                  if (!ReleaseGenerationGameTest.cancelled(old) || sameFuture(old, next)) {
                    throw new AssertionError(
                        "Notification replacement did not cancel old generation");
                  }
                  checkMessages(client, 2, 0);
                  checkToast(client, "generating");
                  return next;
                });
        context.waitFor(
            client ->
                replacement.isDone()
                    && !replacement.isCancelled()
                    && !replacement.isCompletedExceptionally()
                    && ReleaseNotificationUi.settled()
                    && completionVisible(client),
            WAIT_TICKS);
        context.runOnClient(
            client -> {
              checkMessages(client, 2, 1);
              checkToast(client, "complete");
            });

        context.runOnClient(
            client -> {
              command(dispatcher, source, "notification chat false");
              command(dispatcher, source, "notification toast false");
              clear(client);
              command(dispatcher, source, "stbn seed 74124");
              command(dispatcher, source, "stbn generate");
              checkMessages(client, 0, 0);
              checkToast(client, null);
            });
        CompletableFuture<?> quiet =
            context.computeOnClient(client -> ReleaseGenerationGameTest.pending());
        context.waitFor(client -> quiet.isDone() && ReleaseNotificationUi.settled(), WAIT_TICKS);
        context.runOnClient(
            client -> {
              checkMessages(client, 0, 0);
              checkToast(client, null);
            });

        try (GenerationFailure injection = new GenerationFailure()) {
          FailureRequest failed =
              context.computeOnClient(
                  client -> {
                    command(dispatcher, source, "notification chat true");
                    command(dispatcher, source, "notification toast true");
                    clear(client);
                    configure(dispatcher, source, 32, 8, 913736);
                    command(dispatcher, source, "stbn generate");
                    checkMessages(client, 1, 0);
                    checkToast(client, "generating");
                    SystemToast.SystemToastId oldToast = ReleaseNotificationUi.toastId();
                    SystemToast old =
                        Objects.requireNonNull(
                            ReleaseNotificationUi.toasts(client)
                                .getToast(SystemToast.class, oldToast));
                    configure(dispatcher, source, 16, 8, 913736);
                    return new FailureRequest(
                        oldToast, old, ReleaseNotificationUi.prepareFailure());
                  });
          context.waitFor(
              client -> {
                if (!failed.future().isDone()) return false;
                if (!injection.tripped() || !failed.future().isCompletedExceptionally()) {
                  throw new AssertionError("Worker failure did not reach the generation future");
                }
                ReleaseNotificationUi.settleNow();
                checkRetiredToast(failed.toastId(), failed.toast());
                checkToast(client, null);
                configure(dispatcher, source, 16, 8, 74125);
                command(dispatcher, source, "stbn generate");
                checkRetiredToast(failed.toastId(), failed.toast());
                checkMessages(client, 2, 0);
                checkToast(client, "generating");
                return true;
              },
              WAIT_TICKS);
        }
        CompletableFuture<?> afterFailure =
            context.computeOnClient(client -> ReleaseGenerationGameTest.pending());
        context.waitFor(
            client ->
                afterFailure.isDone()
                    && !afterFailure.isCancelled()
                    && !afterFailure.isCompletedExceptionally()
                    && ReleaseNotificationUi.settled()
                    && completionVisible(client),
            WAIT_TICKS);
        context.runOnClient(
            client -> {
              checkMessages(client, 2, 1);
              checkToast(client, "complete");
            });

        CompletableFuture<?> afterCancellation =
            context.computeOnClient(
                client -> {
                  clear(client);
                  configure(dispatcher, source, 256, 128, 913737);
                  command(dispatcher, source, "stbn generate");
                  CompletableFuture<?> cancelled = ReleaseGenerationGameTest.pending();
                  if (cancelled.isDone()) {
                    throw new AssertionError("Cancellation fixture completed before injection");
                  }
                  checkMessages(client, 1, 0);
                  checkToast(client, "generating");
                  SystemToast.SystemToastId oldToast = ReleaseNotificationUi.toastId();
                  SystemToast old =
                      Objects.requireNonNull(
                          ReleaseNotificationUi.toasts(client)
                              .getToast(SystemToast.class, oldToast));
                  if (!cancelled.cancel(false)) {
                    throw new AssertionError("Could not cancel the active generation fixture");
                  }
                  ReleaseNotificationUi.settleNow();
                  checkRetiredToast(oldToast, old);
                  checkToast(client, null);
                  configure(dispatcher, source, 16, 8, 74126);
                  command(dispatcher, source, "stbn generate");
                  checkRetiredToast(oldToast, old);
                  checkMessages(client, 2, 0);
                  checkToast(client, "generating");
                  return ReleaseGenerationGameTest.pending();
                });
        context.waitFor(
            client ->
                afterCancellation.isDone()
                    && !afterCancellation.isCancelled()
                    && !afterCancellation.isCompletedExceptionally()
                    && ReleaseNotificationUi.settled()
                    && completionVisible(client),
            WAIT_TICKS);
        context.runOnClient(
            client -> {
              checkMessages(client, 2, 1);
              checkToast(client, "complete");
            });
      } finally {
        context.runOnClient(
            client -> {
              command(dispatcher, source, "mode set disabled");
              command(dispatcher, source, "stbn size " + original.get("stbnSize").getAsInt());
              command(dispatcher, source, "stbn depth " + original.get("stbnDepth").getAsInt());
              command(dispatcher, source, "stbn seed " + original.get("stbnSeed").getAsLong());
              command(
                  dispatcher,
                  source,
                  "notification chat " + original.get("notifyChat").getAsBoolean());
              command(
                  dispatcher,
                  source,
                  "notification toast " + original.get("notifyToast").getAsBoolean());
              command(
                  dispatcher,
                  source,
                  "mode set " + original.get("mode").getAsString().toLowerCase(Locale.ROOT));
              clear(client);
            });
      }
    }
  }

  private static void configure(
      CommandDispatcher<FabricClientCommandSource> dispatcher,
      FabricClientCommandSource source,
      int size,
      int depth,
      long seed) {
    command(dispatcher, source, "stbn size " + size);
    command(dispatcher, source, "stbn depth " + depth);
    command(dispatcher, source, "stbn seed " + seed);
  }

  private static void command(
      CommandDispatcher<FabricClientCommandSource> dispatcher,
      FabricClientCommandSource source,
      String suffix) {
    ReleaseGenerationGameTest.command(dispatcher, source, suffix);
  }

  @SuppressWarnings("ReferenceEquality")
  private static boolean sameFuture(CompletableFuture<?> left, CompletableFuture<?> right) {
    return left == right;
  }

  private static JsonObject settings() {
    try (var reader =
        Files.newBufferedReader(FabricLoader.getInstance().getConfigDir().resolve("cbbg.json"))) {
      return JsonParser.parseReader(reader).getAsJsonObject();
    } catch (Exception failure) {
      throw new AssertionError("Cannot read packaged CBBG settings", failure);
    }
  }

  private static void clear(Minecraft client) {
    ReleaseNotificationUi.chat(client).clearMessages(false);
    ReleaseNotificationUi.toasts(client).clear();
  }

  private static boolean completionVisible(Minecraft client) {
    String expected = Component.translatable("cbbg.chat.stbn.complete").getString();
    return messages(client).stream().anyMatch(text -> messageCount(text, expected) > 0);
  }

  private static void checkMessages(Minecraft client, int starts, int completions) {
    List<String> messages = messages(client);
    String start = Component.translatable("cbbg.chat.stbn.generating").getString();
    String complete = Component.translatable("cbbg.chat.stbn.complete").getString();
    long actualStarts = messages.stream().mapToLong(text -> messageCount(text, start)).sum();
    long actualCompletions =
        messages.stream().mapToLong(text -> messageCount(text, complete)).sum();
    if (actualStarts != starts || actualCompletions != completions) {
      throw new AssertionError(
          "Wrong generation chat notifications: "
              + actualStarts
              + "/"
              + actualCompletions
              + "; displayed messages: "
              + messages);
    }
  }

  private static long messageCount(String text, String expected) {
    String plain = Objects.requireNonNull(net.minecraft.ChatFormatting.stripFormatting(text));
    if (plain.endsWith(expected)) return 1;
    if (!FabricLoader.getInstance().isModLoaded("chatpatches")) return 0;
    var count = CHAT_PATCHES_COUNT.matcher(plain);
    return count.find() && plain.substring(0, count.start()).endsWith(expected)
        ? Long.parseLong(Objects.requireNonNull(count.group(1)))
        : 0;
  }

  private static List<String> messages(Minecraft client) {
    List<?> messages =
        (List<?>)
            Objects.requireNonNull(
                field(ChatComponent.class, "allMessages", ReleaseNotificationUi.chat(client)));
    return messages.stream()
        .map(
            message -> {
              try {
                var type = message.getClass();
                return ((Component)
                        Objects.requireNonNull(
                            type.getMethod(ReleaseGameNames.noArgMethod(type, "content"))
                                .invoke(message)))
                    .getString();
              } catch (ReflectiveOperationException failure) {
                throw new LinkageError("Minecraft chat message content changed", failure);
              }
            })
        .toList();
  }

  private static void checkToast(Minecraft client, @Nullable String state) {
    String expected =
        state == null
            ? null
            : normalized(Component.translatable("cbbg.toast.stbn." + state).getString());
    String actual = toastText(client);
    if (!Objects.equals(actual, expected)) {
      throw new AssertionError("Expected generation toast " + expected + ", got " + actual);
    }
  }

  private static void checkRetiredToast(
      SystemToast.SystemToastId previousId, SystemToast previousToast) {
    if (previousId.equals(ReleaseNotificationUi.toastId())) {
      throw new AssertionError("Hidden generation toast token was reused");
    }
    if (!Boolean.TRUE.equals(field(SystemToast.class, "forceHide", previousToast))) {
      throw new AssertionError("Old generation toast was not force-hidden");
    }
  }

  @SuppressWarnings("unchecked")
  private static @Nullable String toastText(Minecraft client) {
    SystemToast toast =
        ReleaseNotificationUi.toasts(client)
            .getToast(SystemToast.class, ReleaseNotificationUi.toastId());
    if (toast == null) return null;
    List<FormattedCharSequence> lines =
        (List<FormattedCharSequence>)
            Objects.requireNonNull(field(SystemToast.class, "messageLines", toast));
    StringBuilder text = new StringBuilder();
    for (FormattedCharSequence line : lines) {
      line.accept(
          (index, style, codePoint) -> {
            text.appendCodePoint(codePoint);
            return true;
          });
    }
    return normalized(text.toString());
  }

  private static String normalized(String text) {
    return text.replaceAll("\\s", "");
  }

  private static @Nullable Object field(Class<?> owner, String name, Object instance) {
    try {
      Field field = owner.getDeclaredField(ReleaseGameNames.field(owner, name));
      field.setAccessible(true);
      return field.get(instance);
    } catch (ReflectiveOperationException failure) {
      throw new LinkageError("Minecraft notification UI changed", failure);
    }
  }

  private record FailureRequest(
      SystemToast.SystemToastId toastId, SystemToast toast, CompletableFuture<?> future) {}

  private static final class GenerationFailure extends AbstractAppender implements AutoCloseable {
    private final Logger logger = (Logger) LogManager.getLogger("cbbg-gen");
    private volatile boolean tripped;

    private GenerationFailure() {
      super("cbbg-release-notification-failure", null, null, false, Property.EMPTY_ARRAY);
      start();
      logger.addAppender(this);
    }

    @Override
    public void append(LogEvent event) {
      if (tripped || !event.getThreadName().equals("cbbg-stbn")) return;
      String message = event.getMessage().getFormattedMessage();
      if (message.startsWith("Starting Async STBN Math Generation (32x32x8)")
          || message.startsWith("Checking STBN cache (16x16x8)")) {
        tripped = true;
        throw new AppenderLoggingException("CBBG release worker failure");
      }
    }

    private boolean tripped() {
      return tripped;
    }

    @Override
    public void close() {
      logger.removeAppender(this);
      stop();
    }
  }
}
