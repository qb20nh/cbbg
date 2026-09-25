package com.qb20nh.cbbg.gametest.mixin;

import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.frontend.FrontendRenderPass;
import com.qb20nh.cbbg.gametest.ProcessedRenderObservations;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FrontendRenderPass.class)
public class ProcessedRenderPassMixin {
    @Unique private boolean cbbgTestPass;

    @Inject(method = "setPipeline", at = @At("RETURN"))
    private void cbbgTestPipeline(CompiledRenderPipeline pipeline, CallbackInfo ci) {
        ProcessedRenderObservations.select(pipeline);
        cbbgTestPass = ProcessedRenderObservations.isDither(pipeline);
    }

    @Inject(method = "draw(IIII)V", at = @At("RETURN"))
    private void cbbgTestDraw(int count, int instances, int firstVertex, int firstInstance, CallbackInfo ci) {
        if (cbbgTestPass) ProcessedRenderObservations.recordDraw();
    }
}
