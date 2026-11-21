package com.qb20nh.cbbg.mixin.vulkanmod;

import static org.lwjgl.system.MemoryUtil.memFree;
import static org.lwjgl.vulkan.VK10.vkDestroyDescriptorPool;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.qb20nh.cbbg.render.vulkanmod.VulkanPipelineBuild;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.shader.Pipeline;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.vulkanmod.vulkan.shader.Pipeline$DescriptorSets", remap = false)
abstract class DescriptorAllocationMixin {
  @Shadow @Final private Pipeline pipeline;
  @Shadow private LongBuffer sets;

  @ModifyExpressionValue(
      method = "<init>",
      at =
          @At(
              value = "INVOKE",
              target = "Lorg/lwjgl/system/MemoryUtil;memAllocInt(I)Ljava/nio/IntBuffer;"))
  private IntBuffer cbbg$ownDynamicOffsets(IntBuffer offsets) {
    if (VulkanPipelineBuild.isActive()) VulkanPipelineBuild.own(() -> memFree(offsets));
    return offsets;
  }

  @ModifyExpressionValue(
      method = "createDescriptorSets",
      at =
          @At(
              value = "INVOKE",
              target = "Lorg/lwjgl/system/MemoryUtil;memAllocLong(I)Ljava/nio/LongBuffer;"))
  private LongBuffer cbbg$ownDescriptorHandles(LongBuffer replacement) {
    if (VulkanPipelineBuild.NAME.equals(pipeline.name)) memFree(sets);
    if (VulkanPipelineBuild.isActive()) VulkanPipelineBuild.own(() -> memFree(replacement));
    return replacement;
  }

  @Inject(method = "cleanUp", at = @At("TAIL"))
  private void cbbg$releaseDescriptorHandles(CallbackInfo ci) {
    if (VulkanPipelineBuild.NAME.equals(pipeline.name)) memFree(sets);
  }

  @ModifyExpressionValue(
      method = "createDescriptorPool",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lorg/lwjgl/vulkan/VK10;vkCreateDescriptorPool(Lorg/lwjgl/vulkan/VkDevice;Lorg/lwjgl/vulkan/VkDescriptorPoolCreateInfo;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Ljava/nio/LongBuffer;)I"))
  private int cbbg$ownDescriptorPool(int result, @Local LongBuffer output) {
    return VulkanPipelineBuild.ownHandle(
        result, output, handle -> vkDestroyDescriptorPool(Vulkan.getVkDevice(), handle, null));
  }
}
