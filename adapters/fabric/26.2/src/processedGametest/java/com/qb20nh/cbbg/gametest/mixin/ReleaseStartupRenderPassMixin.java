package com.qb20nh.cbbg.gametest.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import com.qb20nh.cbbg.gametest.ReleaseStartupObservations;
import org.jspecify.annotations.NullMarked;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RenderPass.class)
@NullMarked
public abstract class ReleaseStartupRenderPassMixin {
  @Unique private boolean cbbgTestPass;

  @Inject(method = "setPipeline", at = @At("RETURN"))
  private void cbbgTestPipeline(RenderPipeline pipeline, CallbackInfo ci) {
    String location = pipeline.getLocation().toString();
    cbbgTestPass =
        location.equals("cbbg:pipeline/cbbg_dither") || location.equals("cbbg:pipeline/cbbg_demo");
  }

  @Inject(method = "draw(IIII)V", at = @At("RETURN"))
  private void cbbgTestDraw(
      int count, int instances, int firstVertex, int firstInstance, CallbackInfo ci) {
    if (cbbgTestPass) ReleaseStartupObservations.draw();
  }
}
