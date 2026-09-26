package com.qb20nh.cbbg.mixin;

import com.qb20nh.cbbg.render.DitherController;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.PipelineManager", remap = false)
public abstract class IrisPipelineManagerMixin {
  @Inject(method = "preparePipeline", at = @At("RETURN"), remap = false)
  private void cbbg$refreshShaderTransition(CallbackInfoReturnable<Object> cir) {
    // On world entry Iris creates its pipeline after Minecraft's frame-start
    // hook. Refresh formats before its first draw, not on the following frame.
    DitherController.beginFrame();
  }
}
