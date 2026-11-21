package com.qb20nh.cbbg.mixin;

import com.mojang.blaze3d.vulkan.VulkanRenderPipeline;
import com.qb20nh.cbbg.render.VulkanWindowPresentation;
import java.nio.IntBuffer;
import org.jspecify.annotations.NullMarked;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@NullMarked
@Mixin(VulkanRenderPipeline.class)
public abstract class VulkanRenderPipelineMixin {
  @ModifyArg(
      method = "compile",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lorg/lwjgl/vulkan/VkPipelineRenderingCreateInfoKHR;pColorAttachmentFormats(Ljava/nio/IntBuffer;)Lorg/lwjgl/vulkan/VkPipelineRenderingCreateInfoKHR;"))
  // skipcq: JAVA-W0324
  private static IntBuffer cbbg$windowFormat(IntBuffer formats) {
    int format = VulkanWindowPresentation.compilingFormat();
    if (format != 0) formats.put(formats.position(), format);
    return formats;
  }
}
