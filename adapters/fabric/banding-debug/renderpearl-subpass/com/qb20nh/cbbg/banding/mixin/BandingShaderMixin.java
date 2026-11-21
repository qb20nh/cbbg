package com.qb20nh.cbbg.banding.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPassLayout.Subpass;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.qb20nh.cbbg.banding.BandingPipelines;
import org.jspecify.annotations.NullMarked;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@NullMarked
@Mixin(targets = "com.qb20nh.cbbg.render.DitherPipeline", remap = false)
abstract class BandingShaderMixin {
  @SuppressWarnings("UnusedMethod") // Called by MixinExtras.
  @WrapOperation(
      method = "bind",
      at = @At(value = "INVOKE",
          target = "Lcom/mojang/blaze3d/systems/RenderSystem;getCompiledPipeline"
              + "(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lcom/mojang/renderpearl/api/pipeline/RenderPassLayout$Subpass;)"
              + "Lcom/mojang/renderpearl/api/pipeline/CompiledRenderPipeline;"),
      remap = false)
  private static CompiledRenderPipeline cbbg$select(
      RenderPipeline ordinary, Subpass subpass, Operation<CompiledRenderPipeline> original) {
    return BandingPipelines.compile(ordinary, pipeline -> original.call(pipeline, subpass));
  }
}
