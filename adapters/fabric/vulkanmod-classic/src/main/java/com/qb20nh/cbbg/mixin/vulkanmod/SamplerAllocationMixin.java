package com.qb20nh.cbbg.mixin.vulkanmod;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.qb20nh.cbbg.render.vulkanmod.VulkanAllocationFailure;
import net.vulkanmod.vulkan.texture.SamplerManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = SamplerManager.class, remap = false)
abstract class SamplerAllocationMixin {
  @ModifyExpressionValue(
      method = "createTextureSampler",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lorg/lwjgl/vulkan/VK10;vkCreateSampler(Lorg/lwjgl/vulkan/VkDevice;Lorg/lwjgl/vulkan/VkSamplerCreateInfo;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Ljava/nio/LongBuffer;)I"))
  private static int cbbg$classifySampler(int result) {
    return VulkanAllocationFailure.check(result, "texture sampler");
  }
}
