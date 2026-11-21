package com.qb20nh.cbbg.gametest.mixin;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.qb20nh.cbbg.gametest.ReleaseNoiseTexturesGameTest;
import java.util.Objects;
import java.util.function.Supplier;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@NullMarked
@Mixin(GpuDevice.class)
public abstract class ReleaseNoiseDeviceMixin {
  @Inject(
      method =
          "createTexture(Ljava/util/function/Supplier;ILcom/mojang/blaze3d/GpuFormat;IIII)Lcom/mojang/blaze3d/textures/GpuTexture;",
      at = @At("HEAD"))
  private void before(
      @Nullable Supplier<String> label,
      int usage,
      GpuFormat format,
      int width,
      int height,
      int depth,
      int levels,
      CallbackInfoReturnable<GpuTexture> ci) {
    ReleaseNoiseTexturesGameTest.beforeTexture(label);
  }

  @Inject(
      method =
          "createTexture(Ljava/util/function/Supplier;ILcom/mojang/blaze3d/GpuFormat;IIII)Lcom/mojang/blaze3d/textures/GpuTexture;",
      at = @At("RETURN"))
  private void after(
      @Nullable Supplier<String> label,
      int usage,
      GpuFormat format,
      int width,
      int height,
      int depth,
      int levels,
      CallbackInfoReturnable<GpuTexture> ci) {
    ReleaseNoiseTexturesGameTest.textureCreated(label, Objects.requireNonNull(ci.getReturnValue()));
  }

  @Inject(
      method =
          "createTextureView(Lcom/mojang/blaze3d/textures/GpuTexture;)Lcom/mojang/blaze3d/textures/GpuTextureView;",
      at = @At("HEAD"))
  private void beforeView(GpuTexture texture, CallbackInfoReturnable<GpuTextureView> ci) {
    ReleaseNoiseTexturesGameTest.beforeView(texture);
  }

  @Inject(
      method =
          "createTextureView(Lcom/mojang/blaze3d/textures/GpuTexture;)Lcom/mojang/blaze3d/textures/GpuTextureView;",
      at = @At("RETURN"))
  private void afterView(GpuTexture texture, CallbackInfoReturnable<GpuTextureView> ci) {
    ReleaseNoiseTexturesGameTest.viewCreated(texture, Objects.requireNonNull(ci.getReturnValue()));
  }
}
