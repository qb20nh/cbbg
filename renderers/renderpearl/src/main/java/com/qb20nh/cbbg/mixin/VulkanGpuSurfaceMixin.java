package com.qb20nh.cbbg.mixin;

import static org.lwjgl.vulkan.VK10.*;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuSurface;
import com.qb20nh.cbbg.render.VulkanWindowPresentation;
import it.unimi.dsi.fastutil.longs.LongList;
import org.jspecify.annotations.NullMarked;
import org.lwjgl.vulkan.VkSurfaceCapabilitiesKHR;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@NullMarked
@Mixin(VulkanGpuSurface.class)
public abstract class VulkanGpuSurfaceMixin implements VulkanWindowPresentation.Surface {
  @Shadow @Final private VulkanDevice device;
  @Shadow @Final private int swapchainImageFormat;
  @Shadow @Final private LongList swapchainImages;
  @Shadow private int swapchainWidth;
  @Shadow private int swapchainHeight;
  @Shadow private int currentImageIndex;
  @Shadow private int currentAcquireSemaphore;
  @Shadow @Final private long[] acquireSemaphores;
  @Shadow private long[] presentSemaphores;
  @Shadow private boolean swapchainOutOfDate;
  @Unique private boolean cbbg$colorAttachment;
  @Unique private final VulkanWindowPresentation cbbg$window = new VulkanWindowPresentation();

  @ModifyArg(
      method = "configure",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;imageUsage(I)Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;"))
  private int cbbg$imageUsage(int usage, @Local VkSurfaceCapabilitiesKHR capabilities) {
    cbbg$colorAttachment =
        (capabilities.supportedUsageFlags() & VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT) != 0;
    return cbbg$colorAttachment ? usage | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT : usage;
  }

  @Inject(
      method = "destroySwapchain",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lorg/lwjgl/vulkan/KHRSwapchain;vkDestroySwapchainKHR(Lorg/lwjgl/vulkan/VkDevice;JLorg/lwjgl/vulkan/VkAllocationCallbacks;)V"))
  private void cbbg$closeViews(CallbackInfo ci) {
    cbbg$window.closeViews(device);
  }

  @Override
  public boolean cbbg$canDraw(GpuTextureView input) {
    return cbbg$colorAttachment
        && !swapchainOutOfDate
        && currentImageIndex >= 0
        && (swapchainImageFormat == VK_FORMAT_R8G8B8A8_UNORM
            || swapchainImageFormat == VK_FORMAT_B8G8R8A8_UNORM)
        && input.getWidth(0) == swapchainWidth
        && input.getHeight(0) == swapchainHeight;
  }

  @Override
  public void cbbg$draw(
      CommandEncoder encoder,
      GpuTextureView input,
      GpuTextureView noise,
      float strength,
      float scaleX,
      float scaleY,
      boolean demo) {
    cbbg$window.draw(
        device,
        encoder,
        swapchainImages,
        currentImageIndex,
        swapchainImageFormat,
        acquireSemaphores[currentAcquireSemaphore],
        presentSemaphores[currentImageIndex],
        input,
        noise,
        strength,
        scaleX,
        scaleY,
        demo);
  }
}
