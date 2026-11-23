package com.qb20nh.cbbg.mixin.vulkanmod;

import static org.lwjgl.util.shaderc.Shaderc.shaderc_result_release;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.qb20nh.cbbg.render.vulkanmod.VulkanPipelineBuild;
import net.vulkanmod.vulkan.shader.SPIRVUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = SPIRVUtils.class, remap = false)
abstract class ShaderResultMixin {
  @ModifyExpressionValue(
      method = "compileShader",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lorg/lwjgl/util/shaderc/Shaderc;shaderc_compile_into_spv(JLjava/lang/CharSequence;ILjava/lang/CharSequence;Ljava/lang/CharSequence;J)J"))
  private static long cbbg$ownCompilerResult(long result) {
    if (result != 0 && VulkanPipelineBuild.isActive()) {
      VulkanPipelineBuild.releaseOnClose(() -> shaderc_result_release(result));
    }
    return result;
  }
}
