package com.qb20nh.cbbg.gametest;

import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.render.DitherController;
import com.qb20nh.cbbg.render.GenerationNotifications;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

public final class NotificationsGameTest implements FabricClientGameTest {
  @Override
  public void runTest(ClientGameTestContext context) {
    CbbgConfig original = CbbgConfig.get();
    context.waitFor(client -> DitherController.isReady(), 600);
    CompletableFuture<Void> joining = new CompletableFuture<>();
    try {
      context.runOnClient(
          client -> {
            CbbgConfig.setNotifyChat(true);
            CbbgConfig.setNotifyToast(true);
            clear(client);
            GenerationNotifications.started(joining);
            checkMessages(client, 0); // No chat notification outside a world.
            checkToast(client, "generating");
          });
      try (var world = context.worldBuilder().create()) {
        world.getConnection().waitForChunksRender();
        context.runOnClient(
            client -> {
              checkMessages(client, 1); // The real JOIN event supplies the reminder.
              joining.complete(null);
              GenerationNotifications.tick();
              checkMessages(client, 2);
              checkToast(client, "complete");
              for (boolean chat : new boolean[] {false, true}) {
                for (boolean toast : new boolean[] {false, true}) {
                  CbbgConfig.setNotifyChat(chat);
                  CbbgConfig.setNotifyToast(toast);
                  clear(client);
                  var future = new CompletableFuture<Void>();
                  GenerationNotifications.started(future);
                  checkMessages(client, chat ? 1 : 0);
                  checkToast(client, toast ? "generating" : null);
                  future.complete(null);
                  GenerationNotifications.tick();
                  GenerationNotifications.tick(); // Completion must be delivered once.
                  checkMessages(client, chat ? 2 : 0);
                  checkToast(client, toast ? "complete" : null);
                }
              }
              CbbgConfig.setNotifyChat(true);
              CbbgConfig.setNotifyToast(true);
              clear(client);
              var failed = new CompletableFuture<Void>();
              GenerationNotifications.started(failed);
              failed.completeExceptionally(new IllegalStateException("Injected failure"));
              GenerationNotifications.tick();
              checkMessages(client, 1);
              checkToast(client, "generating"); // Never claim success after failure.

              clear(client);
              var old = new CompletableFuture<Void>();
              var replacement = new CompletableFuture<Void>();
              GenerationNotifications.started(old);
              GenerationNotifications.follow(replacement);
              old.complete(null);
              GenerationNotifications.tick();
              checkMessages(client, 1);
              replacement.complete(null);
              GenerationNotifications.tick();
              checkMessages(client, 2);

              clear(client);
              var cancelled = new CompletableFuture<Void>();
              GenerationNotifications.started(cancelled);
              cancelled.cancel(false);
              GenerationNotifications.tick();
              checkMessages(client, 1);
              clear(client);
              var closed = new CompletableFuture<Void>();
              GenerationNotifications.started(closed);
              GenerationNotifications.close();
              closed.complete(null);
              GenerationNotifications.tick();
              checkMessages(client, 1);
            });
        context.runOnClient(client -> CbbgConfig.setMode(CbbgConfig.Mode.DISABLED));
        context.waitTicks(3);
        context.runOnClient(
            client -> {
              clear(client);
              DitherController.reloadStbn(true);
              checkMessages(client, 1);
            });
        // Real generation and the registered tick callback, with no presentation pass.
        context.waitFor(client -> generationMessages(client).size() == 2, 600);
        context.runOnClient(
            client -> {
              checkToast(client, "complete");
              if (DitherController.isReady()) {
                throw new AssertionError("Completion notification activated disabled rendering");
              }
            });
      }
    } finally {
      context.runOnClient(
          client -> {
            GenerationNotifications.close();
            clear(client);
            CbbgConfig.setNotifyChat(original.notifyChat());
            CbbgConfig.setNotifyToast(original.notifyToast());
            CbbgConfig.setMode(original.mode());
          });
    }
  }

  private static void clear(Minecraft client) {
    client.gui.hud.getChat().clearMessages(false);
    client.gui.toastManager().clear();
  }

  private static void checkMessages(Minecraft client, int expected) {
    List<String> notifications = generationMessages(client);
    if (notifications.size() != expected) {
      throw new AssertionError(
          "Expected " + expected + " generation messages, got " + notifications);
    }
  }

  @SuppressWarnings("unchecked")
  private static List<String> generationMessages(Minecraft client) {
    List<GuiMessage> messages =
        (List<GuiMessage>) field(ChatComponent.class, "allMessages", client.gui.hud.getChat());
    return messages.stream()
        .map(message -> message.content().getString())
        .filter(
            text ->
                text.endsWith(Component.translatable("cbbg.chat.stbn.generating").getString())
                    || text.endsWith(Component.translatable("cbbg.chat.stbn.complete").getString()))
        .toList();
  }

  @SuppressWarnings("unchecked")
  private static void checkToast(Minecraft client, String state) {
    SystemToast toast =
        client
            .gui
            .toastManager()
            .getToast(SystemToast.class, SystemToast.SystemToastId.PERIODIC_NOTIFICATION);
    if (state == null) {
      if (toast != null) {
        throw new AssertionError("Toast preference was ignored");
      }
      return;
    }
    if (toast == null) {
      throw new AssertionError("Missing generation toast");
    }
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
    String expected = Component.translatable("cbbg.toast.stbn." + state).getString();
    if (!text.toString().replaceAll("\\s", "").equals(expected.replaceAll("\\s", ""))) {
      throw new AssertionError("Expected toast " + expected + ", got " + text);
    }
  }

  private static Object field(Class<?> owner, String name, Object instance) {
    try {
      Field field = owner.getDeclaredField(name);
      field.setAccessible(true);
      return field.get(instance);
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError("Could not inspect notification UI", failure);
    }
  }
}
