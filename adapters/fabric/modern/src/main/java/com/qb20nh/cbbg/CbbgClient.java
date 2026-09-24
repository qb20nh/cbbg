package com.qb20nh.cbbg;

import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.compat.iris.IrisCompat;
import com.qb20nh.cbbg.command.CbbgClientCommands;
import com.qb20nh.cbbg.render.DitherController;
import com.qb20nh.cbbg.render.GenerationNotifications;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public final class CbbgClient implements ClientModInitializer {
    public static CbbgConfig.Mode getEffectiveMode() {
        return IrisCompat.isShaderPackActive() ? CbbgConfig.Mode.DISABLED : CbbgConfig.get().mode();
    }

    public static boolean isEnabled() {
        return getEffectiveMode().isActive();
    }

    @Override
    public void onInitializeClient() {
        CbbgConfig.get();
        CbbgClientCommands.register(DitherController::resetAfterToggle, DitherController::reloadStbn);
        ClientTickEvents.END_CLIENT_TICK.register(client -> GenerationNotifications.tick());
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
                GenerationNotifications.onWorldJoin());
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(Cbbg.MOD_ID, "hud_overlay"),
                (graphics, deltaTracker) -> {
                    if (getEffectiveMode() != CbbgConfig.Mode.DEMO) {
                        return;
                    }
                    var font = Minecraft.getInstance().font;
                    int centerX = graphics.guiWidth() / 2;
                    Component left = Component.translatable("cbbg.hud.demo.left");
                    Component right = Component.translatable("cbbg.hud.demo.right");
                    graphics.text(font, left, centerX - 6 - font.width(left), 6, 0xFFFFFFFF, true);
                    graphics.text(font, right, centerX + 6, 6, 0xFFFFFFFF, true);
                });
    }
}
