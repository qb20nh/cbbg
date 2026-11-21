package com.qb20nh.cbbg.banding.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.qb20nh.cbbg.banding.BandingPipelines;
import com.qb20nh.cbbg.render.DitherPass;
import org.jspecify.annotations.NullMarked;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@NullMarked
@Mixin(value = DitherPass.class, remap = false)
abstract class BandingShaderMixin {
  @SuppressWarnings("UnusedMethod") // Called by MixinExtras.
  @WrapOperation(
      method = "render(Lcom/mojang/renderpearl/api/textures/GpuTextureView;"
          + "Lcom/mojang/renderpearl/api/textures/GpuTextureView;FFFZ)Lcom/mojang/blaze3d/pipeline/TextureTarget;",
      at = @At(value = "INVOKE",
          target = "Lcom/mojang/blaze3d/systems/RenderSystem;getCompiledPipeline"
              + "(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;)"
              + "Lcom/mojang/renderpearl/api/pipeline/CompiledRenderPipeline;"),
      remap = false)
  private CompiledRenderPipeline cbbg$select(
      RenderPipeline ordinary, Operation<CompiledRenderPipeline> original) {
    return BandingPipelines.compile(ordinary, original::call, false);
  }
}
