package com.qb20nh.cbbg;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.qb20nh.cbbg.command.CbbgClientCommands;
import com.qb20nh.cbbg.compat.iris.IrisCompat;
import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.mixin.ClassicBackendPlugin;
import com.qb20nh.cbbg.render.ClassicDither;
import com.qb20nh.cbbg.render.ClassicFramebuffers;
import com.qb20nh.cbbg.render.ClassicNoise;
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
    boolean vulkanMod = ClassicBackendPlugin.usesVulkanMod();
    // VulkanMod selects target precision when it begins its next frame.
    CbbgClientCommands.register(
        vulkanMod ? () -> {} : ClassicFramebuffers::tick, ClassicNoise::reload);
    ClientTickEvents.END_CLIENT_TICK.register(
        client -> {
          if (!vulkanMod) {
            ClassicFramebuffers.tick();
            ClassicDither.tick();
          }
          GenerationNotifications.tick();
        });
    ClientPlayConnectionEvents.JOIN.register(
        (handler, sender, client) -> GenerationNotifications.onWorldJoin());
    ClientLifecycleEvents.CLIENT_STOPPING.register(client -> ClassicNoise.close());
    if (vulkanMod) return;
    CoreShaderRegistrationCallback.EVENT.register(
        context ->
            context.register(
                new ResourceLocation("cbbg", "classic_dither"),
                DefaultVertexFormat.POSITION_TEX_COLOR,
                ClassicDither::setShader));
    ClientLifecycleEvents.CLIENT_STOPPING.register(client -> ClassicDither.close());
  }
}
