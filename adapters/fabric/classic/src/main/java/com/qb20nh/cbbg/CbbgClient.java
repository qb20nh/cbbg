package com.qb20nh.cbbg;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.qb20nh.cbbg.command.CbbgClientCommands;
import com.qb20nh.cbbg.compat.iris.IrisCompat;
import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.render.ClassicDither;
import com.qb20nh.cbbg.render.ClassicFramebuffers;
import com.qb20nh.cbbg.render.GenerationNotifications;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback;
import net.minecraft.resources.ResourceLocation;

public final class CbbgClient implements ClientModInitializer {
  public static CbbgConfig.Mode getEffectiveMode() {
    return IrisCompat.isShaderPackActive() ? CbbgConfig.Mode.DISABLED : CbbgConfig.get().mode();
  }

  public static boolean isEnabled() {
    return getEffectiveMode().isActive();
  }

  @Override
  public void onInitializeClient() {
    CbbgClientCommands.register(ClassicFramebuffers::tick, ClassicDither::reload);
    CoreShaderRegistrationCallback.EVENT.register(
        context ->
            context.register(
                new ResourceLocation("cbbg", "classic_dither"),
                DefaultVertexFormat.POSITION_TEX_COLOR,
                ClassicDither::setShader));
    ClientTickEvents.END_CLIENT_TICK.register(
        client -> {
          ClassicFramebuffers.tick();
          ClassicDither.tick();
          GenerationNotifications.tick();
        });
    ClientPlayConnectionEvents.JOIN.register(
        (handler, sender, client) -> GenerationNotifications.onWorldJoin());
    ClientLifecycleEvents.CLIENT_STOPPING.register(client -> ClassicDither.close());
  }
}
