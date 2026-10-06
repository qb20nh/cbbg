package com.qb20nh.cbbg.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.NullMarked;

@NullMarked
final class NotificationPlatform {
  private static SystemToast.SystemToastId toastId = new SystemToast.SystemToastId();

  private NotificationPlatform() {}

  static void chat(Component message) {
    Minecraft.getInstance().gui.getChat().addMessage(message);
    Minecraft.getInstance().getNarrator().say(message);
  }

  static void toast(Component title, Component message) {
    SystemToast.addOrUpdate(Minecraft.getInstance().getToasts(), toastId, title, message);
  }

  static void hideToast() {
    SystemToast.forceHide(Minecraft.getInstance().getToasts(), toastId);
    toastId = new SystemToast.SystemToastId();
  }
}
