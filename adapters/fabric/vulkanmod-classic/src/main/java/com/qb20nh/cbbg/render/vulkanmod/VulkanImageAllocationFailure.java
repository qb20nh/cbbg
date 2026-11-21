package com.qb20nh.cbbg.render.vulkanmod;

import net.vulkanmod.vulkan.util.VkResult;

/** A Vulkan image allocation error that can be retried with a smaller scene format. */
public final class VulkanImageAllocationFailure extends RuntimeException {
  public VulkanImageAllocationFailure(int result) {
    super("Failed to create image: " + VkResult.decode(result));
  }
}
