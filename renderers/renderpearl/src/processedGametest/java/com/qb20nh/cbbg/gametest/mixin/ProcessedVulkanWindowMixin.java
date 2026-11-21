package com.qb20nh.cbbg.gametest.mixin;

import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuSurface;
import com.qb20nh.cbbg.gametest.ReleaseVulkanWindowPixels;
import it.unimi.dsi.fastutil.longs.LongList;
import org.jspecify.annotations.NullMarked;
import org.lwjgl.vulkan.VkSurfaceCapabilitiesKHR;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@NullMarked
@Mixin(VulkanGpuSurface.class)
public abstract class ProcessedVulkanWindowMixin {
  @Shadow @Final private VulkanDevice device;
  @Shadow @Final private int swapchainImageFormat;
  @Shadow @Final private LongList swapchainImages;
  @Shadow private int currentImageIndex;
  @Shadow private int swapchainWidth;
  @Shadow private int swapchainHeight;

  @ModifyArg(
      method = "configure",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;imageUsage(I)Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;"))
  private int cbbgTestReadback(int usage, @Local VkSurfaceCapabilitiesKHR capabilities) {
    if ((capabilities.supportedUsageFlags() & VK_IMAGE_USAGE_TRANSFER_SRC_BIT) == 0)
      throw new AssertionError("Vulkan surface cannot be read back");
    return usage | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
  }

  @Inject(method = "present", at = @At("HEAD"))
  private void cbbgTestPixels(CallbackInfo ci) {
    if (currentImageIndex >= 0)
      ReleaseVulkanWindowPixels.beforePresent(
          device,
          swapchainImages.getLong(currentImageIndex),
          swapchainImageFormat,
          swapchainWidth,
          swapchainHeight);
  }
}
