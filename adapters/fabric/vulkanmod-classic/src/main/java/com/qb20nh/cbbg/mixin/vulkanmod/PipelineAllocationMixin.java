package com.qb20nh.cbbg.mixin.vulkanmod;

import static org.lwjgl.vulkan.VK10.*;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.qb20nh.cbbg.render.vulkanmod.VulkanPipelineBuild;
import java.nio.LongBuffer;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.shader.Pipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = Pipeline.class, remap = false)
abstract class PipelineAllocationMixin {
  @ModifyExpressionValue(
      method = "createDescriptorSetLayout",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lorg/lwjgl/vulkan/VK10;vkCreateDescriptorSetLayout(Lorg/lwjgl/vulkan/VkDevice;Lorg/lwjgl/vulkan/VkDescriptorSetLayoutCreateInfo;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Ljava/nio/LongBuffer;)I"))
  private int cbbg$ownDescriptorLayout(int result, @Local LongBuffer output) {
    return VulkanPipelineBuild.ownHandle(
        result, output, handle -> vkDestroyDescriptorSetLayout(Vulkan.getVkDevice(), handle, null));
  }

  @ModifyExpressionValue(
      method = "createPipelineLayout",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lorg/lwjgl/vulkan/VK10;vkCreatePipelineLayout(Lorg/lwjgl/vulkan/VkDevice;Lorg/lwjgl/vulkan/VkPipelineLayoutCreateInfo;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Ljava/nio/LongBuffer;)I"))
  private int cbbg$ownPipelineLayout(int result, @Local LongBuffer output) {
    return VulkanPipelineBuild.ownHandle(
        result, output, handle -> vkDestroyPipelineLayout(Vulkan.getVkDevice(), handle, null));
  }

  @ModifyExpressionValue(
      method = "createShaderModule",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lorg/lwjgl/vulkan/VK10;vkCreateShaderModule(Lorg/lwjgl/vulkan/VkDevice;Lorg/lwjgl/vulkan/VkShaderModuleCreateInfo;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Ljava/nio/LongBuffer;)I"))
  private static int cbbg$ownShaderModule(int result, @Local LongBuffer output) {
    return VulkanPipelineBuild.ownHandle(
        result, output, handle -> vkDestroyShaderModule(Vulkan.getVkDevice(), handle, null));
  }
}
