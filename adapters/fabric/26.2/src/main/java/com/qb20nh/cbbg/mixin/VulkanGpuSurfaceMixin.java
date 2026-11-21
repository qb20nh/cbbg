package com.qb20nh.cbbg.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuSurface;
import com.qb20nh.cbbg.render.VulkanWindowPresentation;
import com.qb20nh.cbbg.render.VulkanWindowTarget;
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
  @Unique private final VulkanWindowTarget cbbg$window = new VulkanWindowTarget();

  @ModifyArg(
      method = "configure",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;imageUsage(I)Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;"))
  // skipcq: JAVA-W0324
  private int cbbg$imageUsage(int usage, @Local VkSurfaceCapabilitiesKHR capabilities) {
    return cbbg$window.configureImageUsage(usage, capabilities);
  }

  @Inject(
      method = "destroySwapchain",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lorg/lwjgl/vulkan/KHRSwapchain;vkDestroySwapchainKHR(Lorg/lwjgl/vulkan/VkDevice;JLorg/lwjgl/vulkan/VkAllocationCallbacks;)V"))
  private void cbbg$closeViews(CallbackInfo ci) {
    cbbg$window.close(device.vkDevice());
  }

  @Override
  public boolean cbbg$canDraw(GpuTextureView input) {
    return cbbg$window.canDraw(swapchainOutOfDate, currentImageIndex, swapchainImageFormat)
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
    VulkanWindowPresentation.draw(
        cbbg$window,
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
