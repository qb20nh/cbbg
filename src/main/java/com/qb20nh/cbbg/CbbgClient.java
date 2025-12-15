package com.qb20nh.cbbg;

import com.qb20nh.cbbg.compat.IrisCompat;
import com.qb20nh.cbbg.config.CbbgConfig;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.resources.Identifier;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CbbgClient implements ClientModInitializer {

  public static final String MOD_ID = "cbbg";
  public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

  /**
   * Main runtime gate for all cbbg rendering changes.
   *
   * <p>Must be {@code false} when an Iris shaderpack is active to avoid conflicts.
   */
  public static boolean isEnabled() {
    return getEffectiveMode().isActive();
  }

  public static CbbgConfig.Mode getUserMode() {
    return CbbgConfig.get().mode();
  }

  public static CbbgConfig.Mode getEffectiveMode() {
    // Iris shaderpacks must take full control of the pipeline.
    if (IrisCompat.isShaderPackActive()) {
      return CbbgConfig.Mode.DISABLED;
    }
    return CbbgConfig.get().mode();
  }

  public static boolean isDemoMode() {
    return getEffectiveMode() == CbbgConfig.Mode.DEMO;
  }

  public static void renderDemoLabels(GuiGraphics graphics) {
    Minecraft mc = Minecraft.getInstance();
    if (mc == null || mc.font == null) {
      return;
    }

    int w = graphics.guiWidth();
    int centerX = w / 2;
    int y = 6;
    int pad = 6;

    Component left = Component.literal("vanilla");
    Component right = Component.literal("cbbg");

    int leftW = mc.font.width(left);
    int white = 0xFFFFFFFF; // ARGB: required in 1.21.x (0xRRGGBB is fully transparent)
    graphics.drawString(mc.font, left, centerX - pad - leftW, y, white, true);
    graphics.drawString(mc.font, right, centerX + pad, y, white, true);
  }

  @Override
  public void onInitializeClient() {
    // Ensure config is loaded early.
    CbbgConfig.get();

    // Fabric HUD API (recommended; HudRenderCallback is deprecated).
    // Render before chat so we don't get clipped by chat scissor.
    HudElementRegistry.attachElementBefore(
        VanillaHudElements.CHAT,
        Identifier.fromNamespaceAndPath(MOD_ID, "demo_labels"),
        (graphics, tickCounter) -> {
          if (!isDemoMode()) {
            return;
          }
          graphics.nextStratum();
          renderDemoLabels(graphics);
        });

    LOGGER.info("cbbg loaded");
  }
}
