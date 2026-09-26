package com.qb20nh.cbbg.gametest.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import com.qb20nh.cbbg.gametest.EarlyStartupGameTest;
import java.lang.management.ManagementFactory;
import org.jspecify.annotations.NullMarked;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RenderPass.class)
@NullMarked
public class EarlyRenderPassMixin {
  @Unique private boolean cbbgTestPass;

  @Inject(method = "setPipeline", at = @At("RETURN"))
  private void cbbgTestPipeline(RenderPipeline pipeline, CallbackInfo ci) {
    var location = pipeline.getLocation();
    cbbgTestPass =
        location.getNamespace().equals("cbbg")
            && (location.getPath().equals("pipeline/cbbg_dither")
                || location.getPath().equals("pipeline/cbbg_demo"));
  }

  @Inject(method = "draw(IIII)V", at = @At("RETURN"))
  private void cbbgTestDraw(
      int count, int instances, int firstVertex, int firstInstance, CallbackInfo ci) {
    if (cbbgTestPass) {
      EarlyStartupGameTest.draws.incrementAndGet();
      EarlyStartupGameTest.firstDrawMillis.compareAndSet(
          0, ManagementFactory.getRuntimeMXBean().getUptime());
    }
  }
}
