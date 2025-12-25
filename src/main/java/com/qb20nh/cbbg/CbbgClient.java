package com.qb20nh.cbbg;

import com.qb20nh.cbbg.command.CbbgClientCommands;
import com.qb20nh.cbbg.compat.iris.IrisCompat;
import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.render.CbbgDither;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

public final class CbbgClient implements ClientModInitializer {

    /**
     * Main runtime gate for all cbbg rendering changes.
     *
     * <p>
     * Must be {@code false} when an Iris shaderpack is active to avoid conflicts.
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

    public static void renderDemoLabels(GuiGraphics context) {
        Minecraft mc = Minecraft.getInstance();

        int w = mc.getWindow().getGuiScaledWidth();
        int centerX = w / 2;
        int y = 6;
        int pad = 6;

        Component left = Component.translatable("cbbg.hud.demo.left");
        Component right = Component.translatable("cbbg.hud.demo.right");

        int leftW = mc.font.width(left);
        int white = 0xFFFFFFFF; // ARGB: required in 1.21.x (0xRRGGBB is fully transparent)
        context.drawString(mc.font, left, centerX - pad - leftW, y, white, true);
        context.drawString(mc.font, right, centerX + pad, y, white, true);
    }

    @Override
    public void onInitializeClient() {
        // Ensure config is loaded early.
        CbbgConfig.get();

        CbbgClientCommands.register();

        // Ensure generation progress is checked even if not rendering
        ClientTickEvents.END_CLIENT_TICK.register(client -> CbbgDither.ensureStbnLoaded());

        // Notify if generation is still ongoing when joining a world
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (CbbgDither.isGenerating()) {
                client.gui.getChat().addMessage(Component.translatable("cbbg.chat.stbn.generating")
                        .withStyle(ChatFormatting.YELLOW));
            }
        });

        Cbbg.LOGGER.info("cbbg loaded");
    }
}
