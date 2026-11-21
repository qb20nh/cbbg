package com.qb20nh.cbbg.render.vulkanmod;

import static org.lwjgl.vulkan.VK10.*;

import net.vulkanmod.vulkan.util.VkResult;

/** A native scene allocation error that can be retried at lower precision. */
public final class VulkanSceneAllocationFailure extends RuntimeException {
  private VulkanSceneAllocationFailure(String resource, int result) {
    super("Failed to create " + resource + ": " + VkResult.decode(result));
  }

  public static int check(int result, String resource) {
    if ((result == VK_ERROR_OUT_OF_HOST_MEMORY
            || result == VK_ERROR_OUT_OF_DEVICE_MEMORY
            || result == VK_ERROR_FORMAT_NOT_SUPPORTED)
        && VulkanModMainPass.isAllocatingScene()) {
      throw new VulkanSceneAllocationFailure(resource, result);
    }
    return result;
  }
}
