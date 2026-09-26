package com.qb20nh.cbbg.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

final class NotificationPlatform {
  private NotificationPlatform() {}

  static void chat(Component message) {
    Minecraft.getInstance().gui.hud.getChat().addClientSystemMessage(message);
  }

  static void toast(Component title, Component message) {
    SystemToast.addOrUpdate(
        Minecraft.getInstance().gui.toastManager(),
        SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
        title,
        message);
  }
}
