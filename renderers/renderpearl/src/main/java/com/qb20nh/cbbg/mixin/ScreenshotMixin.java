package com.qb20nh.cbbg.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.renderpearl.api.GpuFormat;
import com.qb20nh.cbbg.render.DitherController;
import com.qb20nh.cbbg.render.Rgba8Readback;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Screenshot.class)
public abstract class ScreenshotMixin {
  @Inject(
      method =
          "takeScreenshot(Lcom/mojang/blaze3d/pipeline/RenderTarget;ILjava/util/function/Consumer;)V",
      at = @At("HEAD"),
      cancellable = true)
  private static void cbbg$convertFloatScreenshot(
      RenderTarget target, int downscale, Consumer<NativeImage> callback, CallbackInfo ci) {
    if (target == Minecraft.getInstance().gameRenderer.mainRenderTarget()
        && target.getColorTextureView() != null) {
      var dithered = DitherController.screenshot(target.getColorTextureView());
      if (dithered != null) {
        Screenshot.takeScreenshot(dithered, downscale, callback);
        ci.cancel();
        return;
      }
    }
    var texture = target.getColorTexture();
    if (texture != null
        && (texture.getFormat() == GpuFormat.RGBA16_FLOAT
            || texture.getFormat() == GpuFormat.RGBA32_FLOAT)) {
      Rgba8Readback.capture(target, downscale, callback);
      ci.cancel();
    }
  }
}
