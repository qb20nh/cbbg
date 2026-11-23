package com.qb20nh.cbbg.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.qb20nh.cbbg.CbbgClient;
import com.qb20nh.cbbg.render.ClassicScreenshots;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Screenshot.class)
public abstract class ScreenshotMixin {
  @Inject(method = "takeScreenshot", at = @At("HEAD"), cancellable = true)
  private static void cbbg$capture(
      RenderTarget target, CallbackInfoReturnable<NativeImage> result) {
    if (target == Minecraft.getInstance().getMainRenderTarget() && CbbgClient.isEnabled()) {
      result.setReturnValue(ClassicScreenshots.capture(target));
    }
  }
}
