package com.qb20nh.cbbg.mixin;

import com.mojang.blaze3d.pipeline.PipelineCache;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.qb20nh.cbbg.render.FloatPipelines;
import com.qb20nh.cbbg.render.NeutralVignette;
import org.jspecify.annotations.NullMarked;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PipelineCache.class)
@NullMarked
public abstract class PipelineCacheMixin implements NeutralVignette.SourceHolder {
  @Shadow @Final private ShaderSource shaderSource;

  @Override
  public ShaderSource cbbg$shaderSource() {
    return shaderSource;
  }

  @Inject(method = "insert", at = @At("HEAD"))
  private void cbbg$rememberInserted(
      RenderPipeline pipeline, CompiledRenderPipeline compiled, CallbackInfo ci) {
    FloatPipelines.remember(pipeline, compiled);
  }

  @Inject(method = "get", at = @At("RETURN"))
  private void cbbg$rememberResolved(
      RenderPipeline pipeline, CallbackInfoReturnable<CompiledRenderPipeline> cir) {
    FloatPipelines.remember(pipeline, cir.getReturnValue());
  }
}
