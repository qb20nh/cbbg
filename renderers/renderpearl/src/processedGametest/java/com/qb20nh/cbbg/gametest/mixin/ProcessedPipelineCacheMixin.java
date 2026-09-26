package com.qb20nh.cbbg.gametest.mixin;

import com.mojang.blaze3d.pipeline.PipelineCache;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.qb20nh.cbbg.gametest.ProcessedRenderObservations;
import org.jspecify.annotations.NullMarked;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PipelineCache.class)
@NullMarked
public class ProcessedPipelineCacheMixin {
  @Inject(method = "insert", at = @At("HEAD"))
  private void cbbgTestInsert(
      RenderPipeline source, CompiledRenderPipeline compiled, CallbackInfo ci) {
    ProcessedRenderObservations.remember(source, compiled);
  }

  @Inject(method = "get", at = @At("RETURN"))
  private void cbbgTestGet(
      RenderPipeline source, CallbackInfoReturnable<CompiledRenderPipeline> cir) {
    ProcessedRenderObservations.remember(source, cir.getReturnValue());
  }
}
