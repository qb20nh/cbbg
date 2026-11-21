package com.qb20nh.cbbg.gametest.mixin;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPass;
import com.mojang.renderpearl.util.TextureViewAndSampler;
import com.qb20nh.cbbg.gametest.ProcessedDitherInputs;
import com.qb20nh.cbbg.gametest.ProcessedRenderObservations;
import org.jspecify.annotations.NullMarked;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@NullMarked
@Mixin(VulkanRenderPass.class)
public abstract class ProcessedVulkanPassMixin {
  @Unique private boolean cbbgTestWindow;
  @Unique private int cbbgTestInput;
  @Unique private int cbbgTestNoise;
  @Unique private int cbbgTestInfo;

  @Inject(method = "setPipeline", at = @At("RETURN"))
  private void cbbgTestPipeline(BackendRenderPipeline pipeline, CallbackInfo ci) {
    cbbgTestWindow = ProcessedRenderObservations.isWindowBackend(pipeline);
    cbbgTestInput = ProcessedRenderObservations.windowUniformIndex(pipeline, "InSampler");
    cbbgTestNoise = ProcessedRenderObservations.windowUniformIndex(pipeline, "NoiseSampler");
    cbbgTestInfo = ProcessedRenderObservations.windowUniformIndex(pipeline, "CbbgDitherInfo");
  }

  @ModifyVariable(method = "setUniform", at = @At("HEAD"), argsOnly = true, index = 2)
  private Object cbbgTestInputs(Object original, int index, Object value) {
    if (!cbbgTestWindow) return original;
    if ((index == cbbgTestInput || index == cbbgTestNoise)
        && original instanceof TextureViewAndSampler sampled) {
      return new TextureViewAndSampler(
          ProcessedDitherInputs.texture(
              true, index == cbbgTestInput ? "InSampler" : "NoiseSampler", sampled.view()),
          sampled.sampler());
    }
    if (index == cbbgTestInfo && original instanceof GpuBufferSlice buffer)
      return ProcessedDitherInputs.uniform(true, "CbbgDitherInfo", buffer);
    return original;
  }

  @Inject(method = "setUniform", at = @At("RETURN"))
  private void cbbgTestNoise(int index, Object value, CallbackInfo ci) {
    if (cbbgTestWindow && index == cbbgTestNoise && value instanceof TextureViewAndSampler sampled)
      ProcessedRenderObservations.ditherNoise(sampled.view());
  }

  @Inject(method = "draw(IIII)V", at = @At("RETURN"))
  private void cbbgTestDraw(
      int vertices, int instances, int firstVertex, int firstInstance, CallbackInfo ci) {
    if (cbbgTestWindow) ProcessedRenderObservations.recordWindowDraw();
  }
}
