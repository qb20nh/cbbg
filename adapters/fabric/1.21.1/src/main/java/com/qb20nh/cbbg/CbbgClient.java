package com.qb20nh.cbbg;

import com.qb20nh.cbbg.command.CbbgClientCommands;
import com.qb20nh.cbbg.compat.iris.IrisCompat;
import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.render.DitherController;
import com.qb20nh.cbbg.render.GenerationNotifications;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.NullMarked;

@NullMarked
public final class CbbgClient implements ClientModInitializer {
  public static CbbgConfig.Mode getEffectiveMode() {
    return areShadersActive() ? CbbgConfig.Mode.DISABLED : CbbgConfig.get().mode();
  }

  public static boolean areShadersActive() {
    return IrisCompat.isShaderPackActive();
  }

  public static boolean isEnabled() {
    return getEffectiveMode().isActive();
  }

  public static boolean isDemoMode() {
    return getEffectiveMode() == CbbgConfig.Mode.DEMO;
  }

  @Override
  public void onInitializeClient() {
    CbbgConfig.get();
    CbbgClientCommands.register(DitherController::resetAfterToggle, DitherController::reloadStbn);
    ClientTickEvents.END_CLIENT_TICK.register(client -> GenerationNotifications.tick());
    ClientPlayConnectionEvents.JOIN.register(
        (handler, sender, client) -> GenerationNotifications.onWorldJoin());
    HudRenderCallback.EVENT.register(
        (graphics, deltaTracker) -> {
          if (getEffectiveMode() != CbbgConfig.Mode.DEMO) {
            return;
          }
          var font = Minecraft.getInstance().font;
          int centerX = graphics.guiWidth() / 2;
          Component left = Component.translatable("cbbg.hud.demo.left");
          Component right = Component.translatable("cbbg.hud.demo.right");
          graphics.drawString(font, left, centerX - 6 - font.width(left), 6, 0xFFFFFFFF, true);
          graphics.drawString(font, right, centerX + 6, 6, 0xFFFFFFFF, true);
        });
  }
}
