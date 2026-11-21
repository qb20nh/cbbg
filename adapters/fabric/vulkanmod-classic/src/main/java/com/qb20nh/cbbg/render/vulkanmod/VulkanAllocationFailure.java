package com.qb20nh.cbbg.render.vulkanmod;

import static org.lwjgl.vulkan.VK10.*;

import net.vulkanmod.vulkan.util.VkResult;

/** A recoverable native allocation error while creating CBBG resources. */
public final class VulkanAllocationFailure extends RuntimeException {
  private static final ThreadLocal<Boolean> ALLOCATING = new ThreadLocal<>();

  private VulkanAllocationFailure(String resource, int result) {
    super("Failed to create " + resource + ": " + VkResult.decode(result));
  }

  static void begin() {
    ALLOCATING.set(true);
  }

  static void end() {
    ALLOCATING.remove();
  }

  public static boolean isAllocating() {
    return ALLOCATING.get() != null;
  }

  public static int check(int result, String resource) {
    if ((result == VK_ERROR_OUT_OF_HOST_MEMORY
            || result == VK_ERROR_OUT_OF_DEVICE_MEMORY
            || result == VK_ERROR_TOO_MANY_OBJECTS
            || result == VK_ERROR_FORMAT_NOT_SUPPORTED)
        && isAllocating()) {
      throw new VulkanAllocationFailure(resource, result);
    }
    return result;
  }
}
