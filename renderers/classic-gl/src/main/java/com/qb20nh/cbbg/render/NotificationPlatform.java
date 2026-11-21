package com.qb20nh.cbbg.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import net.minecraft.network.chat.Component;

final class NotificationPlatform {
  private NotificationPlatform() {}

  static void chat(Component message) {
    Minecraft.getInstance().gui.getChat().addMessage(message);
  }

  static void toast(Component title, Component message) {
    var toasts = Minecraft.getInstance().getToasts();
    GenerationToast existing =
        toasts.getToast(GenerationToast.class, SystemToast.SystemToastIds.PERIODIC_NOTIFICATION);
    if (existing == null || existing.hidden) {
      toasts.addToast(new GenerationToast(title, message));
    } else {
      existing.reset(title, message);
    }
  }

  static void hideToast() {
    GenerationToast existing =
        Minecraft.getInstance()
            .getToasts()
            .getToast(GenerationToast.class, SystemToast.SystemToastIds.PERIODIC_NOTIFICATION);
    if (existing != null) existing.hidden = true;
  }

  private static final class GenerationToast extends SystemToast {
    private boolean hidden;

    GenerationToast(Component title, Component message) {
      super(SystemToastIds.PERIODIC_NOTIFICATION, title, message);
    }

    @Override
    public Visibility render(GuiGraphics graphics, ToastComponent component, long time) {
      return hidden ? Visibility.HIDE : super.render(graphics, component, time);
    }
  }
}
