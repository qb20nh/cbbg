package com.qb20nh.cbbg.render;

import static org.lwjgl.vulkan.VK10.*;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.api.textures.*;
import com.mojang.renderpearl.backend.vulkan.*;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import com.mojang.renderpearl.frontend.FrontendRenderPipeline;
import com.mojang.renderpearl.util.TextureViewAndSampler;
import com.qb20nh.cbbg.mixin.VulkanCommandEncoderAccessor;
import it.unimi.dsi.fastutil.longs.LongList;
import java.nio.ByteOrder;
import java.util.Objects;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;

/** Draws into borrowed window images; the surface owns the images and their synchronization. */
@NullMarked
public final class VulkanWindowPresentation {
  public interface Surface {
    boolean cbbg$canDraw(GpuTextureView input);

    void cbbg$draw(
        CommandEncoder encoder,
        GpuTextureView input,
        GpuTextureView noise,
        float strength,
        float scaleX,
        float scaleY,
        boolean demo);
  }

  private static final RenderPipeline ENABLED = pipeline(false);
  private static final RenderPipeline DEMO = pipeline(true);
  private static int compilingFormat;
  private static @Nullable GpuBuffer uniform;
  private static float previousStrength;
  private static float previousScaleX;
  private static float previousScaleY;
  private static int previousNoiseSize;
  private static int previousWidth;
  private static int previousHeight;
  private final VulkanWindowTarget target = new VulkanWindowTarget();

  public static int compilingFormat() {
    return compilingFormat;
  }

  private static RenderPipeline pipeline(boolean demo) {
    var builder =
        RenderPipeline.builder()
            .withLocation(
                Identifier.fromNamespaceAndPath(
                    "cbbg", demo ? "pipeline/window_demo" : "pipeline/window_dither"))
            .withVertexShader("core/screenquad")
            .withFragmentShader(Identifier.fromNamespaceAndPath("cbbg", "core/cbbg_window_dither"))
            .withBindGroupLayout(
                BindGroupLayout.builder()
                    .withUniform("InSampler", UniformType.COMBINED_IMAGE_SAMPLER)
                    .withUniform("NoiseSampler", UniformType.COMBINED_IMAGE_SAMPLER)
                    .withUniform("CbbgDitherInfo", UniformType.UNIFORM_BUFFER)
                    .build())
            .withColorTargetState(ColorTargetState.DEFAULT)
            .withCull(false)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES);
    if (demo) builder.withShaderDefine("CBBG_DEMO");
    return builder.build();
  }

  public void closeViews(VulkanDevice device) {
    target.close(device.vkDevice());
  }

  public static void closeUniform() {
    GpuBuffer previous = uniform;
    uniform = null;
    if (previous != null) previous.close();
  }

  public void draw(
      VulkanDevice device,
      CommandEncoder commands,
      LongList images,
      int index,
      int format,
      long acquire,
      long present,
      GpuTextureView input,
      GpuTextureView noise,
      float strength,
      float scaleX,
      float scaleY,
      boolean demo) {
    CompiledRenderPipeline pipeline;
    compilingFormat = format;
    try {
      pipeline = RenderSystem.getCompiledPipeline(demo ? DEMO : ENABLED);
    } finally {
      compilingFormat = 0;
    }
    int noiseSize = noise.getWidth(0);
    int inputWidth = input.getWidth(0);
    int inputHeight = input.getHeight(0);
    if (uniform == null
        || Float.floatToRawIntBits(strength) != Float.floatToRawIntBits(previousStrength)
        || Float.floatToRawIntBits(scaleX) != Float.floatToRawIntBits(previousScaleX)
        || Float.floatToRawIntBits(scaleY) != Float.floatToRawIntBits(previousScaleY)
        || noiseSize != previousNoiseSize
        || inputWidth != previousWidth
        || inputHeight != previousHeight) {
      var data = commands.transientMemory().allocateCpu(32, 4).order(ByteOrder.nativeOrder());
      data.putFloat(0, strength)
          .putFloat(4, 1.0f / noiseSize)
          .putFloat(8, scaleX)
          .putFloat(12, scaleY)
          .putFloat(16, inputWidth)
          .putFloat(20, inputHeight)
          .putFloat(24, 0)
          .putFloat(28, 0);
      if (uniform == null)
        uniform =
            RenderSystem.getDevice()
                .createBuffer(
                    () -> "CBBG window parameters",
                    GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                    data);
      else commands.writeToBuffer(uniform.slice(), data);
      previousStrength = strength;
      previousScaleX = scaleX;
      previousScaleY = scaleY;
      previousNoiseSize = noiseSize;
      previousWidth = inputWidth;
      previousHeight = inputHeight;
    }
    var encoder = (VulkanCommandEncoder) ((FrontendCommandEncoder) commands).backend();
    int width = input.getWidth(0), height = input.getHeight(0);
    try (var stack = MemoryStack.stackPush()) {
      var command = encoder.allocateAndBeginTransientCommandBuffer();
      target.begin(device.vkDevice(), command, images, index, format, width, height, stack);
      var area = new RenderPass.RenderArea(0, 0, width, height);
      var backend =
          new VulkanRenderPass(
              device,
              encoder,
              command,
              ((VulkanCommandEncoderAccessor) encoder).cbbg$checkpoints(),
              area,
              width,
              height,
              false,
              () -> "CBBG window dither");
      try {
        var frontend = (FrontendRenderPipeline) pipeline;
        var indices = frontend.uniformIndices();
        backend.setPipeline(frontend.backendRenderPipeline());
        backend.setUniform(
            indices.getInt("InSampler"),
            new TextureViewAndSampler(
                input, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST)));
        backend.setUniform(
            indices.getInt("NoiseSampler"),
            new TextureViewAndSampler(
                noise, RenderSystem.getSamplerCache().getRepeat(FilterMode.NEAREST)));
        backend.setUniform(
            indices.getInt("CbbgDitherInfo"), Objects.requireNonNull(uniform).slice());
        backend.draw(3, 1, 0, 0);
      } finally {
        target.end(command, images.getLong(index), stack);
      }
      VulkanWindowTarget.check(vkEndCommandBuffer(command));
      encoder.waitSemaphore(acquire, 0, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT);
      encoder.execute(command);
      encoder.signalSemaphore(present, 0, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT);
    }
  }
}
