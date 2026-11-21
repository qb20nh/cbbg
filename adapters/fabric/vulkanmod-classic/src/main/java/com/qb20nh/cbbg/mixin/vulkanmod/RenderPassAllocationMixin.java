package com.qb20nh.cbbg.mixin.vulkanmod;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.qb20nh.cbbg.render.vulkanmod.VulkanSceneAllocationFailure;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = RenderPass.class, remap = false)
abstract class RenderPassAllocationMixin {
  @ModifyExpressionValue(
      method = "createRenderPass",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lorg/lwjgl/vulkan/VK10;vkCreateRenderPass(Lorg/lwjgl/vulkan/VkDevice;Lorg/lwjgl/vulkan/VkRenderPassCreateInfo;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Ljava/nio/LongBuffer;)I"))
  private int cbbg$classifyRenderPass(int result) {
    return VulkanSceneAllocationFailure.check(result, "render pass");
  }
}
