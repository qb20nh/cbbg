package com.qb20nh.cbbg.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.qb20nh.cbbg.api.DitherOptions;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.client.renderer.MappableRingBuffer;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** Independent GPU pass. Owns its output and uniforms; input and noise textures remain yours. */
@NullMarked
public final class DitherPass implements AutoCloseable {
  private @Nullable TextureTarget output;
  private @Nullable MappableRingBuffer uniform;

  /** Render and close on Minecraft's render thread. The output is valid until resize or close. */
  public TextureTarget render(GpuTextureView input, GpuTextureView noise, DitherOptions options) {
    return render(
        input, noise, options.strength(), options.scaleX(), options.scaleY(), options.demo());
  }

  public TextureTarget render(
      GpuTextureView input,
      GpuTextureView noise,
      float strength,
      float scaleX,
      float scaleY,
      boolean demo) {
    RenderSystem.assertOnRenderThread();
    var pipeline = demo ? DitherPipelines.DEMO : DitherPipelines.ENABLED;
    if (!RenderSystem.getDevice().precompilePipeline(pipeline).isValid()) {
      throw new IllegalStateException("CBBG dither shader could not be compiled");
    }
    int width = input.getWidth(0);
    int height = input.getHeight(0);
    if (output == null || output.width != width || output.height != height) {
      close();
      output =
          new TextureTarget("CBBG utility dither", width, height, false, GpuFormat.RGBA8_UNORM);
    }
    if (uniform == null) {
      uniform =
          new MappableRingBuffer(
              () -> "CBBG utility uniforms",
              GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE,
              16);
    }
    GpuBuffer buffer = uniform.currentBuffer();
    try (GpuBufferSlice.MappedView view = buffer.map(false, true)) {
      Std140Builder.intoBuffer(view.data()).putFloat(strength).putVec2(scaleX, scaleY);
    }
    var encoder = RenderSystem.getDevice().createCommandEncoder();
    try (RenderPass pass =
        encoder.createRenderPass(
            () -> "CBBG utility dither",
            Objects.requireNonNull(output.getColorTextureView()),
            Optional.empty())) {
      pass.setPipeline(pipeline);
      pass.setUniform("CbbgDitherInfo", buffer);
      pass.bindTexture(
          "InSampler", input, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
      pass.bindTexture(
          "NoiseSampler", noise, RenderSystem.getSamplerCache().getRepeat(FilterMode.NEAREST));
      pass.draw(3, 1, 0, 0);
    }
    uniform.rotate();
    return output;
  }

  @Override
  public void close() {
    RenderSystem.assertOnRenderThread();
    if (output != null) {
      output.destroyBuffers();
      output = null;
    }
    if (uniform != null) {
      uniform.close();
      uniform = null;
    }
  }
}
