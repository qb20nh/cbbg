package com.qb20nh.cbbg.gametest.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.textures.GpuTexture;
import com.qb20nh.cbbg.gametest.ReleaseNoiseTexturesGameTest;
import org.jspecify.annotations.NullMarked;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@NullMarked
@Mixin(CommandEncoder.class)
public abstract class ReleaseNoiseWriteMixin {
  @Inject(
      method =
          "writeToTexture(Lcom/mojang/blaze3d/textures/GpuTexture;Lcom/mojang/blaze3d/platform/NativeImage;)V",
      at = @At("HEAD"))
  private void before(GpuTexture texture, NativeImage image, CallbackInfo ci) {
    ReleaseNoiseTexturesGameTest.beforeWrite(texture);
  }

  @Inject(
      method =
          "writeToTexture(Lcom/mojang/blaze3d/textures/GpuTexture;Lcom/mojang/blaze3d/platform/NativeImage;)V",
      at = @At("RETURN"))
  private void after(GpuTexture texture, NativeImage image, CallbackInfo ci) {
    ReleaseNoiseTexturesGameTest.written(texture);
  }
}
