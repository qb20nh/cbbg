package com.qb20nh.cbbg.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.qb20nh.cbbg.api.DitherOptions;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;
import java.util.OptionalInt;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
public final class DitherPass implements AutoCloseable {
  private static final RenderPipeline ENABLED = pipeline(false);
  private static final RenderPipeline DEMO = pipeline(true);
  private @Nullable TextureTarget output;
  private @Nullable MappableRingBuffer uniform;
  private final ByteBuffer data = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder());

  /** Render and close on Minecraft's render thread. The output is valid until resize or close. */
  public TextureTarget render(GpuTextureView input, GpuTextureView noise, DitherOptions options) {
    return render(
        input, noise, options.strength(), options.scaleX(), options.scaleY(), options.demo());
  }

  private static RenderPipeline pipeline(boolean demo) {
    return RenderPlatform.rgba8(RenderPipeline.builder())
        .withLocation(
            Identifier.fromNamespaceAndPath("cbbg", demo ? "pipeline/demo" : "pipeline/dither"))
        .withVertexShader("core/screenquad")
        .withFragmentShader(
            Identifier.fromNamespaceAndPath("cbbg", demo ? "core/cbbg_demo" : "core/cbbg_dither"))
        .withSampler("InSampler")
        .withSampler("NoiseSampler")
        .withUniform("CbbgDitherInfo", UniformType.UNIFORM_BUFFER)
        .withCull(false)
        .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES)
        .build();
  }

  public TextureTarget render(
      GpuTextureView input,
      GpuTextureView noise,
      float strength,
      float scaleX,
      float scaleY,
      boolean demo) {
    RenderSystem.assertOnRenderThread();
    RenderPipeline selected = demo ? DEMO : ENABLED;
    if (!RenderSystem.getDevice().precompilePipeline(selected).isValid()) {
      throw new IllegalStateException("CBBG dither shader could not be compiled");
    }
    int width = input.getWidth(0);
    int height = input.getHeight(0);
    if (output == null || output.width != width || output.height != height) {
      close();
      output = new TextureTarget("CBBG dither", width, height, false);
    }
    if (uniform == null) {
      uniform =
          new MappableRingBuffer(
              () -> "CBBG dither uniforms",
              GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_WRITE,
              16);
    }
    var encoder = RenderSystem.getDevice().createCommandEncoder();
    data.clear();
    data.putFloat(strength).putFloat(0).putFloat(scaleX).putFloat(scaleY).flip();
    encoder.writeToBuffer(uniform.currentBuffer().slice(), data);
    try (RenderPass pass =
        encoder.createRenderPass(
            () -> "CBBG dither",
            Objects.requireNonNull(output.getColorTextureView()),
            OptionalInt.empty())) {
      pass.setPipeline(selected);
      pass.bindTexture(
          "InSampler", input, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
      pass.bindTexture(
          "NoiseSampler", noise, RenderSystem.getSamplerCache().getRepeat(FilterMode.NEAREST));
      pass.setUniform("CbbgDitherInfo", uniform.currentBuffer());
      pass.draw(0, 3);
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
