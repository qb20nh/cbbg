package com.qb20nh.cbbg.mixin.vulkanmod;

import static org.lwjgl.vulkan.VK10.*;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.qb20nh.cbbg.render.vulkanmod.VulkanImageAllocationFailure;
import com.qb20nh.cbbg.render.vulkanmod.VulkanModMainPass;
import net.vulkanmod.vulkan.memory.MemoryManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = MemoryManager.class, remap = false)
abstract class MemoryManagerMixin {
  @ModifyExpressionValue(
      method = "createImage",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lorg/lwjgl/util/vma/Vma;vmaCreateImage(JLorg/lwjgl/vulkan/VkImageCreateInfo;Lorg/lwjgl/util/vma/VmaAllocationCreateInfo;Ljava/nio/LongBuffer;Lorg/lwjgl/PointerBuffer;Lorg/lwjgl/util/vma/VmaAllocationInfo;)I"))
  private int cbbg$classifyImageAllocation(int result) {
    if ((result == VK_ERROR_OUT_OF_HOST_MEMORY
            || result == VK_ERROR_OUT_OF_DEVICE_MEMORY
            || result == VK_ERROR_FORMAT_NOT_SUPPORTED)
        && VulkanModMainPass.isAllocatingScene()) {
      throw new VulkanImageAllocationFailure(result);
    }
    return result;
  }
}
