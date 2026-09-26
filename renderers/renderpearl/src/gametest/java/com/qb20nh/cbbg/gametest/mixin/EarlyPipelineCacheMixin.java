package com.qb20nh.cbbg.gametest.mixin;

import com.mojang.blaze3d.pipeline.PipelineCache;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.qb20nh.cbbg.gametest.EarlyStartupGameTest;
import org.jspecify.annotations.NullMarked;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PipelineCache.class)
@NullMarked
public class EarlyPipelineCacheMixin {
  @Inject(method = "insert", at = @At("HEAD"))
  private void cbbgTestInsert(
      RenderPipeline source, CompiledRenderPipeline compiled, CallbackInfo ci) {
    EarlyStartupGameTest.remember(source, compiled);
  }

  @Inject(method = "get", at = @At("RETURN"))
  private void cbbgTestGet(
      RenderPipeline source, CallbackInfoReturnable<CompiledRenderPipeline> cir) {
    EarlyStartupGameTest.remember(source, cir.getReturnValue());
  }
}
