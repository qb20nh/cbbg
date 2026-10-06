package com.qb20nh.cbbg;

import com.qb20nh.cbbg.command.CbbgClientCommands;
import com.qb20nh.cbbg.compat.iris.IrisCompat;
import com.qb20nh.cbbg.compat.sulkan.SulkanCompat;
import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.render.GenerationNotifications;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NullMarked;

@NullMarked
public final class CbbgClient implements ClientModInitializer {

  /**
   * Main runtime gate for all cbbg rendering changes.
   *
   * <p>Shaderpacks take control of the render pipeline.
   */
  public static boolean isEnabled() {
    return getEffectiveMode().isActive();
  }

  public static CbbgConfig.Mode getUserMode() {
    return CbbgConfig.get().mode();
  }

  public static CbbgConfig.Mode getEffectiveMode() {
    if (IrisCompat.isShaderPackActive() || SulkanCompat.isShaderPackActive()) {
      return CbbgConfig.Mode.DISABLED;
    }
    return CbbgConfig.get().mode();
  }

  public static boolean isDemoMode() {
    return getEffectiveMode() == CbbgConfig.Mode.DEMO;
  }

  public static void renderDemoLabels(GuiGraphicsExtractor context) {
    Minecraft mc = Minecraft.getInstance();

    int w = mc.getWindow().getGuiScaledWidth();
    int centerX = w / 2;
    int y = 6;
    int pad = 6;

    Component left = Component.translatable("cbbg.hud.demo.left");
    Component right = Component.translatable("cbbg.hud.demo.right");

    int leftW = mc.font.width(left);
    int white = 0xFFFFFFFF; // ARGB: required in 1.21.x (0xRRGGBB is fully transparent)
    context.text(mc.font, left, centerX - pad - leftW, y, white, true);
    context.text(mc.font, right, centerX + pad, y, white, true);
  }

  @Override
  public void onInitializeClient() {
    CbbgEarlyInit.configureSettings();
    // Ensure config is loaded early.
    CbbgConfig.get();

    // Register HUD element
    HudElementRegistry.addLast(
        Identifier.fromNamespaceAndPath(Cbbg.MOD_ID, "hud_overlay"),
        (context, _) -> {
          if (!isDemoMode()) {
            return;
          }
          renderDemoLabels(context);
        });

    CbbgClientCommands.register();

    ClientTickEvents.END_CLIENT_TICK.register(_ -> GenerationNotifications.tick());
    ClientPlayConnectionEvents.JOIN.register(
        (_, _, client) -> GenerationNotifications.onWorldJoin());

    Cbbg.LOGGER.info("cbbg loaded");
  }
}
