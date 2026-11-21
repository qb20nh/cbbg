package com.qb20nh.cbbg.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.CommandEncoderBackend;
import com.mojang.blaze3d.systems.GpuSurface;
import com.mojang.blaze3d.systems.GpuSurfaceBackend;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.qb20nh.cbbg.render.CbbgDither;
import org.jspecify.annotations.NullMarked;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@NullMarked
@Mixin(GpuSurface.class)
public abstract class GpuSurfaceMixin {
  @WrapOperation(
      method = "blitFromTexture",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lcom/mojang/blaze3d/systems/GpuSurfaceBackend;blitFromTexture(Lcom/mojang/blaze3d/systems/CommandEncoderBackend;Lcom/mojang/blaze3d/textures/GpuTextureView;)V"))
  @SuppressWarnings("UnusedMethod") // Invoked by MixinExtras at the backend presentation call.
  // skipcq: JAVA-W0324
  private void cbbg$present(
      GpuSurfaceBackend backend,
      CommandEncoderBackend encoder,
      GpuTextureView view,
      Operation<Void> original,
      @Local(argsOnly = true) CommandEncoder commands,
      @Local(argsOnly = true) GpuTextureView input) {
    if (!CbbgDither.presentToVulkanWindow((GpuSurface) (Object) this, commands, input))
      original.call(backend, encoder, view);
  }
}
