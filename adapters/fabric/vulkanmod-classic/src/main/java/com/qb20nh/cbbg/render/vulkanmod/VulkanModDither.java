package com.qb20nh.cbbg.render.vulkanmod;

import static org.lwjgl.system.MemoryUtil.*;
import static org.lwjgl.vulkan.VK10.*;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.qb20nh.cbbg.Cbbg;
import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.render.stbn.STBNGenerator;
import com.qb20nh.cbbg.render.stbn.STBNLoader;
import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.vulkanmod.vulkan.Renderer;
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
  private record NoiseSpec(int size, int depth, long seed) {}

  private final ManualUBO settings = new ManualUBO(2, VK_SHADER_STAGE_FRAGMENT_BIT, 4);
  private GraphicsPipeline pipeline;
  private NoiseSpec requested;
  private CompletableFuture<STBNGenerator.STBNFields> pending;
  private VulkanImage[] noise;
  private int frame;

  void prepare() {
    if (pipeline == null) pipeline = createPipeline();
    CbbgConfig config = CbbgConfig.get();
    var spec = new NoiseSpec(config.stbnSize(), config.stbnDepth(), config.stbnSeed());
    if (!spec.equals(requested)) {
      requested = spec;
      pending = STBNGenerator.generateAsync(spec.size(), spec.size(), spec.depth(), spec.seed());
    }
    if (pending == null || !pending.isDone()) return;
    var finished = pending;
    pending = null;
    try {
      var images =
          STBNLoader.loadOrGenerate(
              spec.size(), spec.size(), spec.depth(), spec.seed(), finished.join());
      STBNGenerator.release(finished);
      if (images == null) {
        pending = STBNGenerator.generateAsync(spec.size(), spec.size(), spec.depth(), spec.seed());
        return;
      }
      var replacement = upload(images);
      freeImages(noise);
      noise = replacement;
      frame = 0;
    } catch (Exception failure) {
      STBNGenerator.release(finished);
      Cbbg.LOGGER.error("Could not prepare VulkanMod STBN textures", failure);
    }
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
      parameters.putFloat(0, noise == null ? 0f : config.strength());
      parameters.putFloat(4, config.mode() == CbbgConfig.Mode.DEMO ? 1f : 0f);
      settings.setSrc(memAddress(parameters), parameters.capacity());
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
              renderer.getBoundRenderPass());
      vkCmdBindPipeline(commands, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.getHandle(state));
      renderer.addUsedPipeline(pipeline);
      renderer.uploadAndBindUBOs(pipeline);
      vkCmdDraw(commands, 3, 1, 0, 0);
    } finally {
      VTextureSelector.bindTexture(0, previousScene);
      VTextureSelector.bindTexture(1, previousNoise);
    }
  }

  private GraphicsPipeline createPipeline() {
    var builder = new Pipeline.Builder(DefaultVertexFormat.POSITION, "cbbg_vulkanmod_dither");
    builder.setUniforms(
        List.of(settings),
        List.of(
            new ImageDescriptor(0, "sampler2D", "DiffuseSampler", 0),
            new ImageDescriptor(1, "sampler2D", "NoiseSampler", 1)));
    String fragment =
        shader("vulkanmod/dither.fsh").replace("// DITHER_MATH", shader("include/dither.glsl"));
    try (var vertex =
            SPIRVUtils.compileShader(
                "cbbg_vulkanmod_dither.vsh",
                shader("vulkanmod/dither.vsh"),
                ShaderKind.VERTEX_SHADER);
        var fragmentCode =
            SPIRVUtils.compileShader(
                "cbbg_vulkanmod_dither.fsh", fragment, ShaderKind.FRAGMENT_SHADER)) {
      builder.setSPIRVs(vertex, fragmentCode);
      return builder.createGraphicsPipeline();
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
    if (pending != null) pending.cancel(false);
    if (pipeline != null) pipeline.cleanUp();
  }
}
