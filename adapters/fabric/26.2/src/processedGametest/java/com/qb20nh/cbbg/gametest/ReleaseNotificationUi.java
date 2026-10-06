package com.qb20nh.cbbg.gametest;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.components.toasts.ToastManager;
import org.jspecify.annotations.NullMarked;

/** Minecraft 26.2 chat, toast, and packaged generation state. */
@NullMarked
final class ReleaseNotificationUi {
  private ReleaseNotificationUi() {}

  static ChatComponent chat(Minecraft client) {
    return client.gui.hud.getChat();
  }

  static ToastManager toasts(Minecraft client) {
    return client.gui.toastManager();
  }

  static boolean settled() {
    try {
      String owner = "com.qb20nh.cbbg.render.GenerationNotifications";
      Class<?> type = Class.forName(ReleaseMapping.className(owner));
      Field field =
          type.getDeclaredField(
              ReleaseMapping.memberName(owner, "java.util.concurrent.CompletableFuture pending"));
      field.setAccessible(true);
      return field.get(null) == null;
    } catch (ReflectiveOperationException failure) {
      throw new LinkageError("Packaged 26.2 generation state changed", failure);
    }
  }

  static SystemToast.SystemToastId toastId() {
    try {
      String owner = "com.qb20nh.cbbg.render.NotificationPlatform";
      Class<?> type = Class.forName(ReleaseMapping.className(owner));
      Field field =
          type.getDeclaredField(
              ReleaseMapping.memberName(
                  owner,
                  "net.minecraft.client.gui.components.toasts.SystemToast$SystemToastId toastId"));
      field.setAccessible(true);
      return (SystemToast.SystemToastId) Objects.requireNonNull(field.get(null));
    } catch (ReflectiveOperationException failure) {
      throw new LinkageError("Packaged 26.2 toast token changed", failure);
    }
  }

  static void settleNow() {
    try {
      String owner = "com.qb20nh.cbbg.render.GenerationNotifications";
      Class<?> type = Class.forName(ReleaseMapping.className(owner));
      Method method = type.getMethod(ReleaseMapping.memberName(owner, "void tick()"));
      method.invoke(null);
    } catch (ReflectiveOperationException failure) {
      throw new LinkageError("Packaged 26.2 generation settlement changed", failure);
    }
  }

  static CompletableFuture<?> prepareFailure() {
    return ReleaseGenerationGameTest.pending();
  }
}
