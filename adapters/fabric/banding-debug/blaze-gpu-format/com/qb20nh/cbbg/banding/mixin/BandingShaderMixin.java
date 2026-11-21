package com.qb20nh.cbbg.banding.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
import com.qb20nh.cbbg.banding.BandingPipelines;
import com.qb20nh.cbbg.render.CbbgDither;
import org.jspecify.annotations.NullMarked;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@NullMarked
@Mixin(value = CbbgDither.class, remap = false)
abstract class BandingShaderMixin {
  @ModifyVariable(method = "drawPass", at = @At("HEAD"), argsOnly = true)
  private static RenderPipeline cbbg$select(RenderPipeline ordinary) {
    var pipeline = BandingPipelines.select(false);
    return pipeline != null && RenderSystem.getDevice().precompilePipeline(pipeline).isValid()
        ? pipeline : ordinary;
  }
}
