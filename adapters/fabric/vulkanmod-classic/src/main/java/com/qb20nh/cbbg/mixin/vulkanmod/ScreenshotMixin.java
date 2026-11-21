package com.qb20nh.cbbg.mixin.vulkanmod;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.vulkanmod.vulkan.Renderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

// VulkanMod overwrites takeScreenshot at the default priority.
@Mixin(value = Screenshot.class, priority = 1100)
abstract class ScreenshotMixin {
  @WrapOperation(
      method = "takeScreenshot",
      at =
          @At(
              value = "INVOKE",
              target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;getColorTextureId()I"))
  private static int cbbg$usePresentedImage(RenderTarget target, Operation<Integer> original) {
    return target == Minecraft.getInstance().getMainRenderTarget()
        ? Renderer.getInstance().getSwapChain().getColorAttachmentGlId()
        : original.call(target);
  }
}
