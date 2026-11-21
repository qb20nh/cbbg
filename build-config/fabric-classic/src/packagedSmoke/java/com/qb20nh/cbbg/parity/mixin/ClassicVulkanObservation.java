package com.qb20nh.cbbg.parity.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.qb20nh.cbbg.parity.ClassicVulkanSmoke;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(targets = "net.vulkanmod.vulkan.Renderer", remap = false)
abstract class ClassicVulkanObservation {
  @ModifyExpressionValue(
      method = "submitFrame",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lorg/lwjgl/vulkan/KHRSwapchain;vkQueuePresentKHR(Lorg/lwjgl/vulkan/VkQueue;Lorg/lwjgl/vulkan/VkPresentInfoKHR;)I"))
  private int cbbg$observePresent(int result) {
    if (result == 0) ClassicVulkanSmoke.observePresent(); // VK_SUCCESS
    return result;
  }
}
