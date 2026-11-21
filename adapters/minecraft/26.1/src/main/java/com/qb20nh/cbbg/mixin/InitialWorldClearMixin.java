package com.qb20nh.cbbg.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.textures.GpuTexture;
import com.qb20nh.cbbg.render.WorldClearScope;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(GameRenderer.class)
public abstract class InitialWorldClearMixin {
  @SuppressWarnings("UnusedMethod") // MixinExtras invokes this wrapper.
  @WrapOperation(
      method = "render",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lcom/mojang/blaze3d/systems/CommandEncoder;clearColorAndDepthTextures(Lcom/mojang/blaze3d/textures/GpuTexture;ILcom/mojang/blaze3d/textures/GpuTexture;D)V"))
  // skipcq: JAVA-W0324
  private void cbbg$clear(
      CommandEncoder encoder,
      GpuTexture color,
      int colorValue,
      GpuTexture depth,
      double depthValue,
      Operation<Void> original,
      DeltaTracker unusedDeltaTracker,
      boolean renderWorld) {
    WorldClearScope.begin(color, colorValue, depth, depthValue, renderWorld);
    try {
      original.call(encoder, color, colorValue, depth, depthValue);
    } finally {
      WorldClearScope.end();
    }
  }
}
