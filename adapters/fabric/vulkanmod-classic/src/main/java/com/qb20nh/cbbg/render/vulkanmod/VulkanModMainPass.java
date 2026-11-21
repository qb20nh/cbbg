package com.qb20nh.cbbg.render.vulkanmod;

import static org.lwjgl.vulkan.VK10.*;

import com.qb20nh.cbbg.CbbgClient;
import com.qb20nh.cbbg.config.CbbgConfig;
import net.vulkanmod.gl.GlTexture;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.pass.MainPass;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkFormatProperties;

/** Renders the main scene offscreen when the effect is active. */
public final class VulkanModMainPass implements MainPass {
  private final Renderer renderer;
  private final MainPass original;
  private final VulkanModDither dither = new VulkanModDither();
  private Scene scene;
  private boolean active;
  private CbbgConfig.PixelFormat requested;
  private int format;

  public VulkanModMainPass(Renderer renderer, MainPass original) {
    this.renderer = renderer;
    this.original = original;
  }

  @Override
  public void begin(VkCommandBuffer commands, MemoryStack stack) {
    active = CbbgClient.isEnabled();
    dither.prepare(active);
    if (!active) {
      if (scene != null) {
        Vulkan.waitIdle();
        releaseScene();
      }
      original.begin(commands, stack);
      return;
    }
    if (requested != CbbgConfig.get().pixelFormat()) {
      requested = CbbgConfig.get().pixelFormat();
      format = selectFormat(requested, stack);
    }
    var swapchain = renderer.getSwapChain();
    int width = swapchain.getWidth();
    int height = swapchain.getHeight();
    if (scene == null || scene.framebuffer.getFormat() != format) {
      if (scene != null) {
        Vulkan.waitIdle();
        releaseScene();
      }
      scene = new Scene(width, height, format);
    } else if (scene.framebuffer.getWidth() != width || scene.framebuffer.getHeight() != height) {
      // VulkanMod waits for the device before updating the swapchain extent.
      scene.framebuffer.resize(width, height);
      GlTexture.bindIdToImage(scene.textureId, scene.framebuffer.getColorAttachment());
    }
    scene.begin(commands, scene.initialPass, stack);
  }

  @Override
  public void end(VkCommandBuffer commands) {
    if (active) {
      renderer.endRenderPass(commands);
      try (MemoryStack stack = MemoryStack.stackPush()) {
        var image = scene.framebuffer.getColorAttachment();
        image.readOnlyLayout(stack, commands);
        var noise = dither.nextNoiseFrame();
        noise.readOnlyLayout(stack, commands);
        original.begin(commands, stack);
        dither.draw(commands, stack, renderer, image, noise);
      }
    }
    original.end(commands);
  }

  @Override
  public void rebindMainTarget() {
    if (!active) {
      original.rebindMainTarget();
      return;
    }
    RenderPass current = renderer.getBoundRenderPass();
    if (current == scene.initialPass || current == scene.reloadPass) return;
    VkCommandBuffer commands = Renderer.getCommandBuffer();
    renderer.endRenderPass(commands);
    try (MemoryStack stack = MemoryStack.stackPush()) {
      scene.begin(commands, scene.reloadPass, stack);
    }
  }

  @Override
  public void bindAsTexture() {
    if (!active) {
      original.bindAsTexture();
      return;
    }
    VkCommandBuffer commands = Renderer.getCommandBuffer();
    RenderPass current = renderer.getBoundRenderPass();
    if (current == scene.initialPass || current == scene.reloadPass) {
      renderer.endRenderPass(commands);
    }
    var image = scene.framebuffer.getColorAttachment();
    try (MemoryStack stack = MemoryStack.stackPush()) {
      image.readOnlyLayout(stack, commands);
    }
    VTextureSelector.bindTexture(image);
  }

  @Override
  public int getColorAttachmentGlId() {
    return active ? scene.textureId : original.getColorAttachmentGlId();
  }

  @Override
  public void cleanUp() {
    dither.close();
    releaseScene();
    original.cleanUp();
  }

  private void releaseScene() {
    if (scene != null) {
      scene.close();
      scene = null;
    }
  }

  @SuppressWarnings("deprecation")
  private static int selectFormat(CbbgConfig.PixelFormat requested, MemoryStack stack) {
    VkFormatProperties properties = VkFormatProperties.malloc(stack);
    if (requested == CbbgConfig.PixelFormat.RGBA32F
        && supportsFormat(VK_FORMAT_R32G32B32A32_SFLOAT, properties)) {
      return VK_FORMAT_R32G32B32A32_SFLOAT;
    }
    if (requested != CbbgConfig.PixelFormat.RGBA8
        && supportsFormat(VK_FORMAT_R16G16B16A16_SFLOAT, properties)) {
      return VK_FORMAT_R16G16B16A16_SFLOAT;
    }
    return VK_FORMAT_R8G8B8A8_UNORM;
  }

  private static boolean supportsFormat(int format, VkFormatProperties properties) {
    vkGetPhysicalDeviceFormatProperties(
        Vulkan.getVkDevice().getPhysicalDevice(), format, properties);
    int required =
        VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT
            | VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BLEND_BIT
            | VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT;
    return (properties.optimalTilingFeatures() & required) == required;
  }

  private static final class Scene {
    private final Framebuffer framebuffer;
    private final RenderPass initialPass;
    private final RenderPass reloadPass;
    private final int textureId;

    Scene(int width, int height, int format) {
      framebuffer = Framebuffer.builder(width, height, 1, true).setFormat(format).build();
      initialPass = createPass(VK_ATTACHMENT_LOAD_OP_DONT_CARE);
      reloadPass = createPass(VK_ATTACHMENT_LOAD_OP_LOAD);
      textureId = GlTexture.genTextureId();
      GlTexture.bindIdToImage(textureId, framebuffer.getColorAttachment());
    }

    private RenderPass createPass(int load) {
      var builder = RenderPass.builder(framebuffer);
      builder
          .getColorAttachmentInfo()
          .setOps(load, VK_ATTACHMENT_STORE_OP_STORE)
          .setFinalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
      builder.getDepthAttachmentInfo().setOps(load, VK_ATTACHMENT_STORE_OP_STORE);
      return builder.build();
    }

    void begin(VkCommandBuffer commands, RenderPass pass, MemoryStack stack) {
      framebuffer.beginRenderPass(commands, pass, stack);
    }

    void close() {
      GlTexture.bindIdToImage(textureId, null);
      GlTexture.glDeleteTextures(textureId);
      framebuffer.cleanUp();
      reloadPass.cleanUp();
      initialPass.cleanUp();
    }
  }
}
