package com.qb20nh.cbbg.render;

import static org.lwjgl.vulkan.VK10.*;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.*;
import com.mojang.blaze3d.vulkan.*;
import com.qb20nh.cbbg.mixin.CommandEncoderAccessor;
import com.qb20nh.cbbg.mixin.VulkanCommandEncoderAccessor;
import it.unimi.dsi.fastutil.longs.LongList;
import java.nio.ByteOrder;
import java.util.Objects;
import java.util.Optional;
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
                    .withSampler("InSampler")
                    .withSampler("NoiseSampler")
                    .withUniform("CbbgDitherInfo", UniformType.UNIFORM_BUFFER)
                    .build())
            .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, 15))
            .withDepthStencilState(Optional.empty())
            .withVertexBinding(0, com.mojang.blaze3d.vertex.VertexFormat.builder(0).build())
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
    RenderPipeline pipeline = demo ? DEMO : ENABLED;
    compilingFormat = format;
    try {
      if (!RenderSystem.getDevice().precompilePipeline(pipeline).isValid())
        throw new IllegalStateException("CBBG window shader could not be compiled");
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
    var encoder = (VulkanCommandEncoder) ((CommandEncoderAccessor) commands).cbbg$backend();
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
        backend.setPipeline(pipeline);
        backend.bindTexture(
            "InSampler", input, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
        backend.bindTexture(
            "NoiseSampler", noise, RenderSystem.getSamplerCache().getRepeat(FilterMode.NEAREST));
        backend.setUniform("CbbgDitherInfo", Objects.requireNonNull(uniform).slice());
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
