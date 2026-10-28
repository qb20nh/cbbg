package com.qb20nh.cbbg.gametest.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vulkan.VulkanRenderPass;
import com.qb20nh.cbbg.gametest.ReleaseVulkanWindowPixels;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@org.jspecify.annotations.NullMarked
@Mixin(VulkanRenderPass.class)
public abstract class ProcessedVulkanDrawMixin {
  @Unique private boolean cbbgTestWindow;

  @Inject(method = "setPipeline", at = @At("HEAD"))
  private void cbbgTestPipeline(RenderPipeline pipeline, CallbackInfo ci) {
    String name = pipeline.getLocation().toString();
    cbbgTestWindow =
        name.equals("cbbg:pipeline/window_dither") || name.equals("cbbg:pipeline/window_demo");
  }

  @Inject(method = "draw", at = @At("RETURN"))
  private void cbbgTestDraw(
      int vertices, int instances, int firstVertex, int firstInstance, CallbackInfo ci) {
    if (cbbgTestWindow) ReleaseVulkanWindowPixels.recordDraw();
  }
}
