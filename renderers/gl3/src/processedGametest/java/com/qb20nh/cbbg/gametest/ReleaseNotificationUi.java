package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.systems.RenderSystem;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import org.jspecify.annotations.NullMarked;

@NullMarked
final class ReleaseNotificationUi {
  private ReleaseNotificationUi() {}

  static ChatComponent chat(Minecraft client) {
    return client.gui.getChat();
  }

  static ToastComponent toasts(Minecraft client) {
    return client.getToasts();
  }

  static boolean settled() {
    RenderSystem.assertOnRenderThread();
    return ReleasePackagedFields.get(
            "com.qb20nh.cbbg.render.GenerationNotifications",
            "java.util.concurrent.CompletableFuture pending")
        == null;
  }

  static SystemToast.SystemToastId toastId() {
    return (SystemToast.SystemToastId)
        Objects.requireNonNull(
            ReleasePackagedFields.get(
                "com.qb20nh.cbbg.render.NotificationPlatform",
                "net.minecraft.client.gui.components.toasts.SystemToast$SystemToastId toastId"));
  }

  static void settleNow() {
    try {
      String owner = "com.qb20nh.cbbg.render.GenerationNotifications";
      Class<?> type = Class.forName(ReleaseMapping.className(owner));
      type.getMethod(ReleaseMapping.memberName(owner, "void tick()")).invoke(null);
    } catch (ReflectiveOperationException failure) {
      throw new LinkageError("Packaged GL3 notification tick changed", failure);
    }
  }

  static CompletableFuture<?> prepareFailure() {
    return ReleaseGenerationGameTest.pending();
  }
}
