package com.qb20nh.cbbg.mixin.vulkanmod;

import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32B32A32_SFLOAT;

import net.vulkanmod.vulkan.texture.VulkanImage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = VulkanImage.Builder.class, remap = false)
abstract class FloatImageMixin {
  @Inject(method = "formatSize", at = @At("HEAD"), cancellable = true)
  private static void cbbg$floatFormatSize(int format, CallbackInfoReturnable<Integer> result) {
    if (format == VK_FORMAT_R16G16B16A16_SFLOAT) result.setReturnValue(8);
    if (format == VK_FORMAT_R32G32B32A32_SFLOAT) result.setReturnValue(16);
  }
}
