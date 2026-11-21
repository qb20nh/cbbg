package com.qb20nh.cbbg.mixin.vulkanmod;

import static org.lwjgl.vulkan.VK10.vkDestroyFramebuffer;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.qb20nh.cbbg.render.vulkanmod.SceneFramebufferAccess;
import com.qb20nh.cbbg.render.vulkanmod.VulkanAllocationFailure;
import it.unimi.dsi.fastutil.objects.Reference2LongArrayMap;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = Framebuffer.class, remap = false)
abstract class FramebufferAllocationMixin implements SceneFramebufferAccess {
  @Shadow private VulkanImage colorAttachment;
  @Shadow protected VulkanImage depthAttachment;
  @Shadow @Final private Reference2LongArrayMap<RenderPass> renderpassToFramebufferMap;

  @Shadow
  protected abstract long getFramebufferId(RenderPass pass);

  @WrapMethod(method = "createImages")
  private void cbbg$releaseUnsubmittedAttachments(Operation<Void> original) {
    if (!VulkanAllocationFailure.isAllocating()) {
      original.call();
      return;
    }
    // Resizing has already queued the old attachments for disposal.
    colorAttachment = null;
    depthAttachment = null;
    try {
      original.call();
    } catch (RuntimeException failure) {
      cbbg$discardUnsubmitted();
      throw failure;
    }
  }

  @ModifyExpressionValue(
      method = "createFramebuffer",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lorg/lwjgl/vulkan/VK10;vkCreateFramebuffer(Lorg/lwjgl/vulkan/VkDevice;Lorg/lwjgl/vulkan/VkFramebufferCreateInfo;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Ljava/nio/LongBuffer;)I"))
  private int cbbg$classifyFramebuffer(int result) {
    return VulkanAllocationFailure.check(result, "framebuffer");
  }

  @Override
  public void cbbg$prepareHandles(RenderPass initial, RenderPass reload) {
    getFramebufferId(initial);
    getFramebufferId(reload);
  }

  @Override
  public void cbbg$discardUnsubmitted() {
    for (long handle : renderpassToFramebufferMap.values().toLongArray()) {
      vkDestroyFramebuffer(Vulkan.getVkDevice(), handle, null);
    }
    renderpassToFramebufferMap.clear();
    if (colorAttachment != null) colorAttachment.doFree();
    if (depthAttachment != null) depthAttachment.doFree();
    colorAttachment = null;
    depthAttachment = null;
  }
}
