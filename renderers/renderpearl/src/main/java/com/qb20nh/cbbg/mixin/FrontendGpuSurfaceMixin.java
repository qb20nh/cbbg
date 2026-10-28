package com.qb20nh.cbbg.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.GpuSurfaceBackend;
import com.mojang.renderpearl.frontend.FrontendGpuSurface;
import com.qb20nh.cbbg.render.DitherPresentation;
import org.jspecify.annotations.NullMarked;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@NullMarked
@Mixin(FrontendGpuSurface.class)
public abstract class FrontendGpuSurfaceMixin {
  @WrapOperation(
      method = "blitFromTexture",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lcom/mojang/renderpearl/backend/api/GpuSurfaceBackend;blitFromTexture(Lcom/mojang/renderpearl/backend/api/CommandEncoderBackend;Lcom/mojang/renderpearl/api/textures/GpuTextureView;)V"))
  @SuppressWarnings("UnusedMethod") // Invoked by MixinExtras at the backend presentation call.
  private void cbbg$present(
      GpuSurfaceBackend backend,
      CommandEncoderBackend encoder,
      GpuTextureView view,
      Operation<Void> original,
      @Local(argsOnly = true) CommandEncoder commands,
      @Local(argsOnly = true) GpuTextureView input) {
    if (!DitherPresentation.presentToVulkanWindow((GpuSurface) this, commands, input))
      original.call(backend, encoder, view);
  }
}
