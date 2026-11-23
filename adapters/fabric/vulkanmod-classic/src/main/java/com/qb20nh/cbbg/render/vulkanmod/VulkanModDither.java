package com.qb20nh.cbbg.render.vulkanmod;

import static org.lwjgl.system.MemoryUtil.*;
import static org.lwjgl.vulkan.VK10.*;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.qb20nh.cbbg.Cbbg;
import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.render.ClassicNoise;
import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.Pipeline;
import net.vulkanmod.vulkan.shader.PipelineState;
import net.vulkanmod.vulkan.shader.SPIRVUtils;
import net.vulkanmod.vulkan.shader.SPIRVUtils.ShaderKind;
import net.vulkanmod.vulkan.shader.descriptor.ImageDescriptor;
import net.vulkanmod.vulkan.shader.descriptor.ManualUBO;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

final class VulkanModDither {
  private final ManualUBO settings = new ManualUBO(2, VK_SHADER_STAGE_FRAGMENT_BIT, 4);
  private GraphicsPipeline pipeline;
  private RenderPass preparedPass;
  private long pipelineHandle;
  private boolean failed;
  private boolean noiseUploadFailed;
  private VulkanImage[] noise;
  private int frame;

  void prepare(boolean active) {
    if (active || ClassicNoise.isPreparing()) ClassicNoise.tick(this::replaceNoise);
  }

  boolean preparePipeline(RenderPass pass) {
    if (failed) return false;
    if (preparedPass == pass) return true;
    try {
      if (pipeline == null) pipeline = createPipeline();
      var state =
          new PipelineState(
              PipelineState.AssemblyRasterState.encode(
                  false, VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST, VK_POLYGON_MODE_FILL),
              0,
              0,
              0,
              VK_COLOR_COMPONENT_R_BIT
                  | VK_COLOR_COMPONENT_G_BIT
                  | VK_COLOR_COMPONENT_B_BIT
                  | VK_COLOR_COMPONENT_A_BIT,
              pass);
      pipelineHandle = pipeline.getHandle(state);
      preparedPass = pass;
      return true;
    } catch (RuntimeException failure) {
      failed = true;
      Cbbg.LOGGER.error("Could not prepare the VulkanMod dither pipeline", failure);
      if (pipeline != null) {
        if (preparedPass != null) Vulkan.waitIdle();
        pipeline.cleanUp();
        pipeline = null;
      }
      preparedPass = null;
      pipelineHandle = VK_NULL_HANDLE;
      return false;
    }
  }

  boolean hasFailed() {
    return failed;
  }

  void allowPipelineRetry() {
    failed = false;
  }

  void retryNoiseUpload() {
    if (noiseUploadFailed) ClassicNoise.reload(false);
  }

  private void replaceNoise(NativeImage[] images) {
    VulkanImage[] replacement;
    VulkanAllocationException.begin();
    try {
      replacement = upload(images);
      noiseUploadFailed = false;
    } catch (VulkanAllocationException failure) {
      noiseUploadFailed = true;
      throw failure;
    } finally {
      VulkanAllocationException.end();
    }
    freeImages(noise);
    noise = replacement;
    frame = 0;
  }

  private static VulkanImage[] upload(NativeImage[] images) {
    var textures = new VulkanImage[images.length];
    try {
      for (int index = 0; index < images.length; index++) {
        var image = images[index];
        int width = image.getWidth();
        int height = image.getHeight();
        var pixels = memAlloc(width * height * Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        try {
          for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) pixels.putInt(image.getPixelRGBA(x, y));
          }
          pixels.flip();
          var texture =
              VulkanImage.builder(width, height)
                  .setFormat(VK_FORMAT_R8G8B8A8_UNORM)
                  .setUsage(VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT)
                  .createVulkanImage();
          textures[index] = texture;
          texture.uploadSubTextureAsync(0, width, height, 0, 0, width, 0, 0, pixels);
        } finally {
          memFree(pixels);
        }
      }
      return textures;
    } catch (RuntimeException failure) {
      freeImages(textures);
      throw failure;
    } finally {
      for (var image : images) image.close();
    }
  }

  VulkanImage nextNoiseFrame() {
    var noiseFrame = noise == null ? VTextureSelector.getWhiteTexture() : noise[frame];
    if (noise != null) frame = (frame + 1) % noise.length;
    return noiseFrame;
  }

  void draw(
      VkCommandBuffer commands,
      MemoryStack stack,
      Renderer renderer,
      VulkanImage scene,
      VulkanImage noiseFrame) {
    var previousScene = VTextureSelector.getBoundTexture(0);
    var previousNoise = VTextureSelector.getBoundTexture(1);
    VTextureSelector.bindTexture(0, scene);
    VTextureSelector.bindTexture(1, noiseFrame);
    try {
      CbbgConfig config = CbbgConfig.get();
      var parameters = stack.calloc(16);
      parameters.putFloat(0, noise == null || ClassicNoise.hasFailed() ? 0f : config.strength());
      parameters.putFloat(4, config.mode() == CbbgConfig.Mode.DEMO ? 1f : 0f);
      settings.setSrc(memAddress(parameters), parameters.capacity());
      vkCmdBindPipeline(commands, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineHandle);
      renderer.addUsedPipeline(pipeline);
      renderer.uploadAndBindUBOs(pipeline);
      vkCmdDraw(commands, 3, 1, 0, 0);
    } finally {
      VTextureSelector.bindTexture(0, previousScene);
      VTextureSelector.bindTexture(1, previousNoise);
    }
  }

  private GraphicsPipeline createPipeline() {
    var builder = new Pipeline.Builder(DefaultVertexFormat.POSITION, VulkanPipelineBuild.NAME);
    builder.setUniforms(
        List.of(settings),
        List.of(
            new ImageDescriptor(0, "sampler2D", "DiffuseSampler", 0),
            new ImageDescriptor(1, "sampler2D", "NoiseSampler", 1)));
    String fragment =
        shader("vulkanmod/dither.fsh").replace("// DITHER_MATH", shader("include/dither.glsl"));
    try (var build = VulkanPipelineBuild.begin();
        var vertex =
            SPIRVUtils.compileShader(
                VulkanPipelineBuild.NAME + ".vsh",
                shader("vulkanmod/dither.vsh"),
                ShaderKind.VERTEX_SHADER);
        var fragmentCode =
            SPIRVUtils.compileShader(
                VulkanPipelineBuild.NAME + ".fsh", fragment, ShaderKind.FRAGMENT_SHADER)) {
      builder.setSPIRVs(vertex, fragmentCode);
      var created = builder.createGraphicsPipeline();
      build.commit();
      return created;
    }
  }

  private static String shader(String name) {
    try (var stream = VulkanModDither.class.getResourceAsStream("/assets/cbbg/shaders/" + name)) {
      if (stream == null) throw new IllegalStateException("Missing CBBG shader " + name);
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException failure) {
      throw new IllegalStateException("Could not read CBBG shader " + name, failure);
    }
  }

  private static void freeImages(VulkanImage[] images) {
    if (images == null) return;
    for (var image : images) if (image != null) image.free();
  }

  void close() {
    freeImages(noise);
    if (pipeline != null) pipeline.cleanUp();
  }
}
