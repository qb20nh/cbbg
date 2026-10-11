package com.qb20nh.cbbg.gametest.mixin;

import com.mojang.blaze3d.opengl.GlRenderPass;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.qb20nh.cbbg.gametest.ReleaseStartupObservations;
import org.jspecify.annotations.NullMarked;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GlRenderPass.class)
@NullMarked
public abstract class ReleaseStartupRenderPassMixin {
  @Unique private boolean cbbgTestPass;

  @Inject(method = "setPipeline", at = @At("RETURN"))
  private void cbbgTestPipeline(RenderPipeline pipeline, CallbackInfo ci) {
    String location = pipeline.getLocation().toString();
    cbbgTestPass = location.equals("cbbg:pipeline/dither") || location.equals("cbbg:pipeline/demo");
  }

  @Inject(method = "draw(II)V", at = @At("RETURN"))
  private void cbbgTestDraw(int count, int instances, CallbackInfo ci) {
    if (cbbgTestPass) ReleaseStartupObservations.draw();
  }
}
