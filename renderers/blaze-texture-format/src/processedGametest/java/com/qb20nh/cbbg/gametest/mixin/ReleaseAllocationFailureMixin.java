package com.qb20nh.cbbg.gametest.mixin;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.TextureFormat;
import com.qb20nh.cbbg.gametest.ReleaseAllocationGameTest;
import java.util.function.Supplier;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Rejects only fixture-owned main color allocations before they reach the real GPU device. */
@NullMarked
@Mixin(GpuDevice.class)
public abstract class ReleaseAllocationFailureMixin {
  @Inject(
      method =
          "createTexture(Ljava/util/function/Supplier;ILcom/mojang/blaze3d/textures/TextureFormat;IIII)Lcom/mojang/blaze3d/textures/GpuTexture;",
      at = @At("HEAD"))
  private void cbbg$rejectMainColor(
      @Nullable Supplier<String> label,
      int usage,
      TextureFormat format,
      int width,
      int height,
      int depth,
      int levels,
      CallbackInfoReturnable<GpuTexture> cir) {
    ReleaseAllocationGameTest.reject(label, format);
  }
}
