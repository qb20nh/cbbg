package com.qb20nh.cbbg.mixin;

import com.mojang.blaze3d.pipeline.PipelineCache;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.qb20nh.cbbg.render.FloatPipelines;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PipelineCache.class)
public abstract class PipelineCacheMixin {
    @Inject(method = "insert", at = @At("HEAD"))
    private void cbbg$rememberInserted(RenderPipeline pipeline, CompiledRenderPipeline compiled,
            CallbackInfo ci) {
        FloatPipelines.remember(pipeline, compiled);
    }

    @Inject(method = "get", at = @At("RETURN"))
    private void cbbg$rememberResolved(RenderPipeline pipeline,
            CallbackInfoReturnable<CompiledRenderPipeline> cir) {
        FloatPipelines.remember(pipeline, cir.getReturnValue());
    }
}
