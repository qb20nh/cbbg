package com.qb20nh.cbbg.gametest.mixin;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.frontend.FrontendRenderPass;
import com.qb20nh.cbbg.gametest.ProcessedDitherInputs;
import com.qb20nh.cbbg.gametest.ProcessedRenderObservations;
import java.util.List;
import java.util.Optional;
import org.joml.Vector4fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FrontendRenderPass.class)
public class ProcessedRenderPassMixin {
  @Shadow @Final
  private List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> colorAttachments;

  @Unique private boolean cbbgTestPass;

  @Inject(method = "setPipeline", at = @At("RETURN"))
  private void cbbgTestPipeline(CompiledRenderPipeline pipeline, CallbackInfo ci) {
    ProcessedRenderObservations.select(pipeline);
    cbbgTestPass = ProcessedRenderObservations.isDither(pipeline);
    if (cbbgTestPass) {
      ProcessedRenderObservations.ditherOutput(colorAttachments.getFirst().textureView());
    }
  }

  @ModifyVariable(
      method =
          "setUniform(Ljava/lang/String;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Lcom/mojang/renderpearl/api/textures/GpuSampler;)V",
      at = @At("HEAD"),
      argsOnly = true,
      index = 2)
  private GpuTextureView cbbgTestTexture(
      GpuTextureView original, String name, GpuTextureView view, GpuSampler sampler) {
    return ProcessedDitherInputs.texture(cbbgTestPass, name, original);
  }

  @Inject(
      method =
          "setUniform(Ljava/lang/String;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Lcom/mojang/renderpearl/api/textures/GpuSampler;)V",
      at = @At("RETURN"))
  private void cbbgTestBoundTexture(
      String name, GpuTextureView view, GpuSampler sampler, CallbackInfo ci) {
    if (cbbgTestPass && name.equals("NoiseSampler")) {
      ProcessedRenderObservations.ditherNoise(view);
    }
  }

  @ModifyVariable(
      method = "setUniform(Ljava/lang/String;Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;)V",
      at = @At("HEAD"),
      argsOnly = true,
      index = 2)
  private GpuBufferSlice cbbgTestUniform(
      GpuBufferSlice original, String name, GpuBufferSlice buffer) {
    return ProcessedDitherInputs.uniform(cbbgTestPass, name, original);
  }

  @Inject(method = "draw(IIII)V", at = @At("RETURN"))
  private void cbbgTestDraw(
      int count, int instances, int firstVertex, int firstInstance, CallbackInfo ci) {
    if (cbbgTestPass) ProcessedRenderObservations.recordDraw();
  }
}
