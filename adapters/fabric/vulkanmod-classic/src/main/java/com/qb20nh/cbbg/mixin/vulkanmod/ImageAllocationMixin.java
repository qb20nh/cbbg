package com.qb20nh.cbbg.mixin.vulkanmod;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.qb20nh.cbbg.render.vulkanmod.VulkanAllocationFailure;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = VulkanImage.class, remap = false)
abstract class ImageAllocationMixin {
  @ModifyExpressionValue(
      method = "createImageView(JIIII)J",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lorg/lwjgl/vulkan/VK10;vkCreateImageView(Lorg/lwjgl/vulkan/VkDevice;Lorg/lwjgl/vulkan/VkImageViewCreateInfo;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Ljava/nio/LongBuffer;)I"))
  private static int cbbg$classifyImageView(int result) {
    return VulkanAllocationFailure.check(result, "image view");
  }

  @WrapOperation(
      method = "createTextureImage",
      at =
          @At(
              value = "INVOKE",
              target = "Lnet/vulkanmod/vulkan/texture/VulkanImage;createImageView(JIII)J"))
  private static long cbbg$releaseImageWithoutView(
      long id,
      int format,
      int aspect,
      int levels,
      Operation<Long> original,
      @Local(index = 1) VulkanImage image) {
    try {
      return original.call(id, format, aspect, levels);
    } catch (RuntimeException failure) {
      if (VulkanAllocationFailure.isAllocating()) image.doFree();
      throw failure;
    }
  }

  @WrapOperation(
      method = "createTextureImage",
      at =
          @At(
              value = "INVOKE",
              target = "Lnet/vulkanmod/vulkan/texture/SamplerManager;getTextureSampler(BB)J"))
  private static long cbbg$releaseImageWithoutSampler(
      byte mipLevels, byte flags, Operation<Long> original, @Local(index = 1) VulkanImage image) {
    try {
      return original.call(mipLevels, flags);
    } catch (RuntimeException failure) {
      if (VulkanAllocationFailure.isAllocating()) image.doFree();
      throw failure;
    }
  }
}
