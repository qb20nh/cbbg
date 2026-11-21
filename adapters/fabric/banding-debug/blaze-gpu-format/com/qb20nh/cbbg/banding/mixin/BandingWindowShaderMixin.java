package com.qb20nh.cbbg.banding.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.qb20nh.cbbg.banding.BandingPipelines;
import com.qb20nh.cbbg.render.VulkanWindowPresentation;
import org.jspecify.annotations.NullMarked;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@NullMarked
@Mixin(value = VulkanWindowPresentation.class, remap = false)
abstract class BandingWindowShaderMixin {
  @ModifyVariable(method = "draw", at = @At("STORE"))
  private static RenderPipeline cbbg$select(RenderPipeline ordinary) {
    var pipeline = BandingPipelines.select(true);
    return pipeline == null ? ordinary : pipeline;
  }
}
