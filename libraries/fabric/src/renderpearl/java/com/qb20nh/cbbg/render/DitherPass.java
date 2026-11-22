package com.qb20nh.cbbg.render;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.qb20nh.cbbg.api.DitherOptions;
import java.nio.ByteOrder;
import java.util.Objects;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** Owns the RGBA8 output and uniform buffer; Minecraft owns pipelines. */
@NullMarked
public final class DitherPass implements AutoCloseable {
  private @Nullable TextureTarget output;
  private @Nullable GpuBuffer uniform;
  private float previousStrength;
  private float previousScaleX;
  private float previousScaleY;

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
    int width = input.getWidth(0);
    int height = input.getHeight(0);
    if (output == null || output.width != width || output.height != height) {
      close();
      output = new TextureTarget("CBBG dither", width, height, GpuFormat.RGBA8_UNORM, null);
    }
    var device = RenderSystem.getDevice();
    var encoder = device.createCommandEncoder();
    if (uniform == null
        || Float.floatToRawIntBits(strength) != Float.floatToRawIntBits(previousStrength)
        || Float.floatToRawIntBits(scaleX) != Float.floatToRawIntBits(previousScaleX)
        || Float.floatToRawIntBits(scaleY) != Float.floatToRawIntBits(previousScaleY)) {
      var data = encoder.transientMemory().allocateCpu(16, 4).order(ByteOrder.nativeOrder());
      data.putFloat(0, strength);
      data.putFloat(4, 0);
      data.putFloat(8, scaleX);
      data.putFloat(12, scaleY);
      if (uniform == null) {
        uniform =
            device.createBuffer(
                () -> "CBBG dither parameters",
                GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                data);
      } else {
        encoder.writeToBuffer(uniform.slice(), data);
      }
      previousStrength = strength;
      previousScaleX = scaleX;
      previousScaleY = scaleY;
    }
    try (RenderPass pass =
        DitherPipeline.open(encoder, Objects.requireNonNull(output.getColorTextureView()))) {
      DitherPipeline.bind(pass, demo);
      pass.setUniform(
          "InSampler", input, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
      pass.setUniform(
          "NoiseSampler", noise, RenderSystem.getSamplerCache().getRepeat(FilterMode.NEAREST));
      pass.setUniform("CbbgDitherInfo", Objects.requireNonNull(uniform).slice());
      pass.draw(3, 1, 0, 0);
    }
    return output;
  }

  @Override
  public void close() {
    RenderSystem.assertOnRenderThread();
    TextureTarget previousOutput = output;
    GpuBuffer previousUniform = uniform;
    output = null;
    uniform = null;
    try {
      if (previousOutput != null) previousOutput.destroyBuffers();
    } finally {
      if (previousUniform != null) previousUniform.close();
    }
  }
}
