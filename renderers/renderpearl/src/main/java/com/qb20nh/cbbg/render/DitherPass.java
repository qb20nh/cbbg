package com.qb20nh.cbbg.render;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.nio.ByteOrder;
import java.util.Optional;
import net.minecraft.resources.Identifier;

/** Owns the RGBA8 output; Minecraft owns pipelines and transient uniform memory. */
public final class DitherPass implements AutoCloseable {
  private static final RenderPipeline ENABLED = pipeline(false);
  private static final RenderPipeline DEMO = pipeline(true);
  private TextureTarget output;

  private static RenderPipeline pipeline(boolean demo) {
    var builder =
        RenderPipeline.builder()
            .withLocation(
                Identifier.fromNamespaceAndPath("cbbg", demo ? "pipeline/demo" : "pipeline/dither"))
            .withVertexShader("core/screenquad")
            .withFragmentShader(Identifier.fromNamespaceAndPath("cbbg", "core/cbbg_dither"))
            .withBindGroupLayout(
                BindGroupLayout.builder()
                    .withUniform("InSampler", UniformType.COMBINED_IMAGE_SAMPLER)
                    .withUniform("NoiseSampler", UniformType.COMBINED_IMAGE_SAMPLER)
                    .withUniform("CbbgDitherInfo", UniformType.UNIFORM_BUFFER)
                    .build())
            .withColorTargetState(ColorTargetState.DEFAULT)
            .withCull(false)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES);
    if (demo) {
      builder.withShaderDefine("CBBG_DEMO");
    }
    return builder.build();
  }

  public TextureTarget render(
      GpuTextureView input,
      GpuTextureView noise,
      float strength,
      float scaleX,
      float scaleY,
      boolean demo) {
    RenderSystem.assertOnRenderThread();
    int width = input.getWidth(0);
    int height = input.getHeight(0);
    if (output == null || output.width != width || output.height != height) {
      close();
      output = new TextureTarget("CBBG dither", width, height, GpuFormat.RGBA8_UNORM, null);
    }
    var device = RenderSystem.getDevice();
    var encoder = device.createCommandEncoder();
    var data = encoder.transientMemory().allocateCpu(16, 4).order(ByteOrder.nativeOrder());
    data.putFloat(0, strength);
    data.putFloat(4, 0);
    data.putFloat(8, scaleX);
    data.putFloat(12, scaleY);
    var uniform =
        encoder
            .transientMemory()
            .uploadGpu(
                data,
                device.getDeviceInfo().limits().minUniformOffsetAlignment(),
                GpuBuffer.USAGE_UNIFORM);
    try (RenderPass pass =
        encoder.createRenderPass(
            () -> "CBBG dither", output.getColorTextureView(), Optional.empty())) {
      pass.setPipeline(RenderSystem.getCompiledPipeline(demo ? DEMO : ENABLED));
      pass.setUniform(
          "InSampler", input, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
      pass.setUniform(
          "NoiseSampler", noise, RenderSystem.getSamplerCache().getRepeat(FilterMode.NEAREST));
      pass.setUniform("CbbgDitherInfo", uniform);
      pass.draw(3, 1, 0, 0);
    }
    return output;
  }

  @Override
  public void close() {
    if (output != null) {
      output.destroyBuffers();
      output = null;
    }
  }
}
