package com.qb20nh.cbbg.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.qb20nh.cbbg.CbbgClient;
import com.qb20nh.cbbg.render.CbbgDither;
import com.qb20nh.cbbg.render.Rgba8Readback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Screenshot.class)
public abstract class ScreenshotMixin {
  @Inject(method = "takeScreenshot", at = @At("HEAD"), cancellable = true)
  @SuppressWarnings("ReferenceEquality") // Identify the client's main framebuffer by instance.
  private static void cbbg$capture(RenderTarget target, CallbackInfoReturnable<NativeImage> info) {
    if (target != Minecraft.getInstance().getMainRenderTarget() || !CbbgClient.isEnabled()) return;
    var output = CbbgDither.renderScreenshotTarget(target);
    // Screenshot passes reuse the displayed noise index and never advance presentation state.
    info.setReturnValue(
        output == null ? Rgba8Readback.capture(target) : Rgba8Readback.readScreenshot(output));
  }
}
