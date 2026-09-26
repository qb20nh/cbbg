package com.qb20nh.cbbg.gametest;

import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.appender.AppenderLoggingException;
import org.apache.logging.log4j.core.config.Property;

/** Exercises packaged notification behavior through commands and Minecraft UI. */
public final class ReleaseNotificationsGameTest implements FabricClientGameTest {
  private static final int WAIT_TICKS = 600;

  @Override
  public void runTest(ClientGameTestContext context) {
    JsonObject original = ReleaseClient.settings().deepCopy();
    FabricClientCommandSource source =
        (FabricClientCommandSource)
            Proxy.newProxyInstance(
                FabricClientCommandSource.class.getClassLoader(),
                new Class<?>[] {FabricClientCommandSource.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("sendFeedback")
                      || method.getName().equals("sendError")) {
                    return null;
                  }
                  throw new AssertionError("Unexpected command source call: " + method);
                });
    CommandDispatcher<FabricClientCommandSource> dispatcher;
    try (var world = context.worldBuilder().create()) {
      world.getConnection().waitForChunksRender();
      dispatcher = context.computeOnClient(client -> ClientCommands.getActiveDispatcher());
    }
    if (dispatcher == null) throw new AssertionError("No client command dispatcher");
    context.waitFor(client -> client.level == null, WAIT_TICKS);

    boolean restored = false;
    try (GenerationControl control = new GenerationControl()) {
      context.runOnClient(
          client -> {
            execute(dispatcher, source, "mode set disabled");
            execute(dispatcher, source, "stbn size 16");
            execute(dispatcher, source, "stbn depth 8");
            execute(dispatcher, source, "stbn seed 7100");
            preferences(dispatcher, source, true, true);
            clear(client);
          });
      Gate joining = control.arm(false);
      context.runOnClient(
          client -> {
            execute(dispatcher, source, "stbn generate");
            checkMessages(client, 0, 0);
            checkToast(client, "generating");
          });
      awaitGate(context, joining);
      try (var world = context.worldBuilder().create()) {
        world.getConnection().waitForChunksRender();
        try {
          context.runOnClient(client -> checkMessages(client, 1, 0));
          joining.release();
          awaitCompletion(context, 7100, true, true, 1);
          long seed = 7101;
          for (boolean chat : new boolean[] {false, true}) {
            for (boolean toast : new boolean[] {false, true}) {
              long requestedSeed = seed++;
              Gate generation = control.arm(false);
              context.runOnClient(
                  client -> {
                    preferences(dispatcher, source, chat, toast);
                    execute(dispatcher, source, "stbn seed " + requestedSeed);
                    clear(client);
                    execute(dispatcher, source, "stbn generate");
                    checkMessages(client, chat ? 1 : 0, 0);
                    checkToast(client, toast ? "generating" : null);
                  });
              awaitGate(context, generation);
              generation.release();
              awaitCompletion(context, requestedSeed, chat, toast, chat ? 1 : 0);
            }
          }
          ReleaseClient.assertNoDraws(context);

          Gate failed = control.arm(true);
          context.runOnClient(
              client -> {
                preferences(dispatcher, source, true, true);
                execute(dispatcher, source, "stbn seed 7200");
                clear(client);
                execute(dispatcher, source, "stbn generate");
              });
          awaitGate(context, failed);
          context.waitFor(client -> failed.workerIdle(), WAIT_TICKS);
          context.waitTicks(5);
          context.runOnClient(
              client -> {
                checkMessages(client, 1, 0);
                checkToast(client, "generating");
              });

          Gate old = control.arm(false);
          context.runOnClient(
              client -> {
                execute(dispatcher, source, "stbn seed 7300");
                clear(client);
                execute(dispatcher, source, "stbn generate");
              });
          awaitGate(context, old);
          Gate replacement = control.arm(false);
          context.runOnClient(
              client -> {
                execute(dispatcher, source, "stbn seed 7301");
                execute(dispatcher, source, "stbn generate");
              });
          awaitGate(context, replacement);
          context.waitTicks(3);
          context.runOnClient(
              client -> {
                if (!old.interrupted)
                  throw new AssertionError("Replacement did not cancel the old worker");
                checkMessages(client, 2, 0);
                checkToast(client, "generating");
              });
          replacement.release();
          awaitCompletion(context, 7301, true, true, 2);

          Gate followed = control.arm(false);
          context.runOnClient(
              client -> {
                execute(dispatcher, source, "stbn seed 7400");
                clear(client);
                execute(dispatcher, source, "stbn generate");
              });
          awaitGate(context, followed);
          // Disabled frames discard their loading reference. Re-enabling implicitly
          // replaces that request, and pending notifications must follow it.
          context.waitTicks(3);
          Gate implicit = control.arm(false);
          context.runOnClient(client -> execute(dispatcher, source, "mode set enabled"));
          awaitGate(context, implicit);
          context.runOnClient(
              client -> {
                if (!followed.interrupted)
                  throw new AssertionError("Implicit load did not cancel the old worker");
                checkMessages(client, 1, 0);
              });
          implicit.release();
          awaitCompletion(context, 7400, true, true, 1);
        } finally {
          control.releaseAll();
          restore(context, dispatcher, source, original);
          restored = true;
        }
      }
    } finally {
      if (!restored) restore(context, dispatcher, source, original);
      context.runOnClient(ReleaseNotificationsGameTest::clear);
    }
  }

  private static void awaitGate(ClientGameTestContext context, Gate gate) {
    context.waitFor(client -> gate.worker != null, WAIT_TICKS);
  }

  private static void awaitCompletion(
      ClientGameTestContext context, long seed, boolean chat, boolean toast, int starts) {
    ReleaseClient.awaitCache(context, 16, 8, seed);
    if (chat || toast) {
      context.waitFor(
          client ->
              chat
                  ? generationMessages(client).stream()
                      .anyMatch(
                          text ->
                              text.endsWith(
                                  Component.translatable("cbbg.chat.stbn.complete").getString()))
                  : normalized(Component.translatable("cbbg.toast.stbn.complete").getString())
                      .equals(toastText(client)),
          WAIT_TICKS);
    } else {
      // With both channels off, a completed render load supplies the observable end point.
      // Enabling may replace a pending load; notifications must follow that replacement.
      long before = ProcessedRenderObservations.draws();
      ReleaseClient.command(context, "mode set enabled");
      ReleaseClient.awaitDrawAfter(context, before);
    }
    context.waitTicks(5);
    context.runOnClient(
        client -> {
          checkMessages(client, starts, chat ? 1 : 0);
          checkToast(client, toast ? "complete" : null);
        });
    context.waitTicks(5);
    context.runOnClient(client -> checkMessages(client, starts, chat ? 1 : 0));
    if (!chat && !toast) {
      ReleaseClient.command(context, "mode set disabled");
      ReleaseClient.assertNoDraws(context);
    }
  }

  private static void preferences(
      CommandDispatcher<FabricClientCommandSource> dispatcher,
      FabricClientCommandSource source,
      boolean chat,
      boolean toast) {
    execute(dispatcher, source, "notification chat " + chat);
    execute(dispatcher, source, "notification toast " + toast);
  }

  private static void restore(
      ClientGameTestContext context,
      CommandDispatcher<FabricClientCommandSource> dispatcher,
      FabricClientCommandSource source,
      JsonObject original) {
    context.runOnClient(
        client -> {
          execute(dispatcher, source, "mode set disabled");
          execute(dispatcher, source, "stbn size " + original.get("stbnSize").getAsInt());
          execute(dispatcher, source, "stbn depth " + original.get("stbnDepth").getAsInt());
          execute(dispatcher, source, "stbn seed " + original.get("stbnSeed").getAsLong());
          preferences(
              dispatcher,
              source,
              original.get("notifyChat").getAsBoolean(),
              original.get("notifyToast").getAsBoolean());
          execute(
              dispatcher,
              source,
              "mode set " + original.get("mode").getAsString().toLowerCase(Locale.ROOT));
        });
    context.waitFor(client -> original.equals(ReleaseClient.settings()), WAIT_TICKS);
  }

  private static void execute(
      CommandDispatcher<FabricClientCommandSource> dispatcher,
      FabricClientCommandSource source,
      String suffix) {
    try {
      if (dispatcher.execute("cbbg " + suffix, source) != 1) {
        throw new AssertionError("Command failed: " + suffix);
      }
    } catch (CommandSyntaxException failure) {
      throw new AssertionError("Command failed: " + suffix, failure);
    }
  }

  private static void clear(Minecraft client) {
    client.gui.hud.getChat().clearMessages(false);
    client.gui.toastManager().clear();
  }

  private static void checkMessages(Minecraft client, int starts, int completions) {
    List<String> texts = generationMessages(client);
    long actualStarts =
        texts.stream()
            .filter(
                text ->
                    text.endsWith(Component.translatable("cbbg.chat.stbn.generating").getString()))
            .count();
    long actualCompletions =
        texts.stream()
            .filter(
                text ->
                    text.endsWith(Component.translatable("cbbg.chat.stbn.complete").getString()))
            .count();
    if (actualStarts != starts || actualCompletions != completions) {
      throw new AssertionError(
          "Expected generation messages " + starts + "/" + completions + ", got " + texts);
    }
  }

  @SuppressWarnings("unchecked")
  private static List<String> generationMessages(Minecraft client) {
    List<GuiMessage> messages =
        (List<GuiMessage>) field(ChatComponent.class, "allMessages", client.gui.hud.getChat());
    return messages.stream().map(message -> message.content().getString()).toList();
  }

  private static void checkToast(Minecraft client, String state) {
    String actual = toastText(client);
    String expected =
        state == null
            ? null
            : normalized(Component.translatable("cbbg.toast.stbn." + state).getString());
    if (!java.util.Objects.equals(actual, expected)) {
      throw new AssertionError("Expected toast " + expected + ", got " + actual);
    }
  }

  @SuppressWarnings("unchecked")
  private static String toastText(Minecraft client) {
    SystemToast toast =
        client
            .gui
            .toastManager()
            .getToast(SystemToast.class, SystemToast.SystemToastId.PERIODIC_NOTIFICATION);
    if (toast == null) return null;
    List<FormattedCharSequence> lines =
        (List<FormattedCharSequence>) field(SystemToast.class, "messageLines", toast);
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

  private static Object field(Class<?> owner, String name, Object instance) {
    try {
      Field field = owner.getDeclaredField(name);
      field.setAccessible(true);
      return field.get(instance);
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError("Could not inspect Minecraft notification UI", failure);
    }
  }

  private static final class Gate {
    private final CountDownLatch release = new CountDownLatch(1);
    private final boolean fail;
    private volatile Thread worker;
    private volatile boolean interrupted;

    private Gate(boolean fail) {
      this.fail = fail;
    }

    private void release() {
      release.countDown();
    }

    private boolean workerIdle() {
      Thread thread = worker;
      if (thread == null) return false;
      for (StackTraceElement frame : thread.getStackTrace()) {
        if (frame.getClassName().equals("java.util.concurrent.ThreadPoolExecutor")
            && frame.getMethodName().equals("getTask")) return true;
      }
      return false;
    }
  }

  private static final class GenerationControl extends AbstractAppender implements AutoCloseable {
    private final Logger logger = (Logger) LogManager.getLogger("cbbg-gen");
    private final AtomicReference<Gate> next = new AtomicReference<>();
    private final java.util.concurrent.CopyOnWriteArrayList<Gate> gates =
        new java.util.concurrent.CopyOnWriteArrayList<>();

    private GenerationControl() {
      super("cbbg-release-notifications", null, null, false, Property.EMPTY_ARRAY);
      start();
      logger.addAppender(this);
    }

    private Gate arm(boolean fail) {
      Gate gate = new Gate(fail);
      gates.add(gate);
      if (!next.compareAndSet(null, gate))
        throw new AssertionError("Generation gate already armed");
      return gate;
    }

    @Override
    public void append(LogEvent event) {
      if (!event.getThreadName().equals("cbbg-stbn")
          || !event
              .getMessage()
              .getFormattedMessage()
              .startsWith("Starting Async STBN Math Generation (16x16x8)")) return;
      Gate gate = next.getAndSet(null);
      if (gate == null) return;
      gate.worker = Thread.currentThread();
      if (gate.fail) throw new AppenderLoggingException("Injected generation failure");
      try {
        gate.release.await();
      } catch (InterruptedException cancelled) {
        gate.interrupted = true;
        Thread.currentThread().interrupt();
      }
    }

    private void releaseAll() {
      next.set(null);
      gates.forEach(Gate::release);
    }

    @Override
    public void close() {
      releaseAll();
      logger.removeAppender(this);
      stop();
    }
  }
}
