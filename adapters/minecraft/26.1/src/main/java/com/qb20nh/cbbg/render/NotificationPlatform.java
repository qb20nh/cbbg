package com.qb20nh.cbbg.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.NullMarked;

@NullMarked
final class NotificationPlatform {
  private NotificationPlatform() {}

  static void chat(Component message) {
    Minecraft.getInstance().gui.getChat().addClientSystemMessage(message);
    Minecraft.getInstance().getNarrator().saySystemQueued(message);
  }

  static void toast(Component title, Component message) {
    SystemToast.addOrUpdate(
        Minecraft.getInstance().getToastManager(),
        SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
        title,
        message);
  }
}
