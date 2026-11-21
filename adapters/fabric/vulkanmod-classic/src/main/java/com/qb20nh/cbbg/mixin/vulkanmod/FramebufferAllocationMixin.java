package com.qb20nh.cbbg.mixin.vulkanmod;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.qb20nh.cbbg.render.vulkanmod.VulkanModMainPass;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(value = Framebuffer.class, remap = false)
abstract class FramebufferAllocationMixin {
  @Shadow private VulkanImage colorAttachment;
  @Shadow protected VulkanImage depthAttachment;

  @WrapMethod(method = "createImages")
  private void cbbg$releaseUnsubmittedAttachments(Operation<Void> original) {
    if (!VulkanModMainPass.isAllocatingScene()) {
      original.call();
      return;
    }
    // Resizing has already queued the old attachments for disposal.
    colorAttachment = null;
    depthAttachment = null;
    try {
      original.call();
    } catch (RuntimeException failure) {
      if (colorAttachment != null) colorAttachment.doFree();
      if (depthAttachment != null) depthAttachment.doFree();
      colorAttachment = null;
      depthAttachment = null;
      throw failure;
    }
  }
}
