package com.qb20nh.cbbg.mixin.vulkanmod;

import static org.lwjgl.system.MemoryUtil.memFree;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.qb20nh.cbbg.render.vulkanmod.VulkanPipelineBuild;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import org.lwjgl.vulkan.VkVertexInputAttributeDescription;
import org.lwjgl.vulkan.VkVertexInputBindingDescription;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = GraphicsPipeline.class, remap = false)
abstract class VertexDescriptionAllocationMixin {
  @ModifyExpressionValue(
      method = "getBindingDescription",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lorg/lwjgl/vulkan/VkVertexInputBindingDescription;calloc(I)Lorg/lwjgl/vulkan/VkVertexInputBindingDescription$Buffer;"))
  private static VkVertexInputBindingDescription.Buffer cbbg$ownBindingDescription(
      VkVertexInputBindingDescription.Buffer bindings) {
    if (VulkanPipelineBuild.isActive()) VulkanPipelineBuild.own(() -> memFree(bindings));
    return bindings;
  }

  @ModifyExpressionValue(
      method = "getAttributeDescriptions",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lorg/lwjgl/vulkan/VkVertexInputAttributeDescription;calloc(I)Lorg/lwjgl/vulkan/VkVertexInputAttributeDescription$Buffer;"))
  private static VkVertexInputAttributeDescription.Buffer cbbg$ownAttributeDescriptions(
      VkVertexInputAttributeDescription.Buffer attributes) {
    if (VulkanPipelineBuild.isActive()) VulkanPipelineBuild.own(() -> memFree(attributes));
    return attributes;
  }
}
