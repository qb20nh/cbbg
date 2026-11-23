package com.qb20nh.cbbg.mixin.vulkanmod;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.qb20nh.cbbg.render.vulkanmod.VulkanAllocationException;
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
    return VulkanAllocationException.check(result, "image");
  }
}
