package com.qb20nh.cbbg.gametest.mixin;

import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.qb20nh.cbbg.gametest.ReleaseObservations;
import org.jspecify.annotations.NullMarked;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RenderPass.class)
@NullMarked
public abstract class ReleaseNoiseMixin {
  @Inject(method = "bindTexture", at = @At("HEAD"))
  private void cbbg$noise(
      String name, GpuTextureView texture, GpuSampler sampler, CallbackInfo ci) {
    ReleaseObservations.bind(name, texture);
  }
}
