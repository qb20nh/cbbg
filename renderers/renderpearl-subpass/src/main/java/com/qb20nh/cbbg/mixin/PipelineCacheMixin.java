package com.qb20nh.cbbg.mixin;

import com.mojang.blaze3d.pipeline.PipelineCache;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.qb20nh.cbbg.render.FloatRenderPasses;
import org.jspecify.annotations.NullMarked;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@NullMarked
@Mixin(PipelineCache.class)
abstract class PipelineCacheMixin {
  @Inject(method = "insert", at = @At("HEAD"))
  private void cbbg$rememberInserted(
      PipelineCache.Key key, CompiledRenderPipeline pipeline, CallbackInfo ci) {
    FloatRenderPasses.remember(key.pipeline(), pipeline);
  }

  @Inject(
      method =
          "get(Lcom/mojang/blaze3d/pipeline/PipelineCache$Key;)Lcom/mojang/renderpearl/api/pipeline/CompiledRenderPipeline;",
      at = @At("RETURN"))
  private void cbbg$rememberResolved(
      PipelineCache.Key key, CallbackInfoReturnable<CompiledRenderPipeline> ci) {
    var pipeline = ci.getReturnValue();
    if (pipeline != null) FloatRenderPasses.remember(key.pipeline(), pipeline);
  }
}
