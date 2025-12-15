package com.qb20nh.cbbg.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.qb20nh.cbbg.CbbgClient;
import com.qb20nh.cbbg.render.CbbgDither;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Screenshot.class)
public abstract class ScreenshotMixin {

  private static final ThreadLocal<Integer> CAPTURE_DEPTH = ThreadLocal.withInitial(() -> 0);

  @Inject(
      method = "takeScreenshot(Lcom/mojang/blaze3d/pipeline/RenderTarget;ILjava/util/function/Consumer;)V",
      at = @At("HEAD"),
      cancellable = true)
  private static void cbbg$takeScreenshot(
      RenderTarget target, int downscaleFactor, Consumer<NativeImage> callback, CallbackInfo ci) {
    if (CAPTURE_DEPTH.get() > 0) {
      return;
    }
    if (!CbbgClient.isEnabled()) {
      return;
    }

    Minecraft mc = Minecraft.getInstance();
    if (mc == null) {
      return;
    }
    if (target != mc.getMainRenderTarget()) {
      return;
    }

    RenderSystem.assertOnRenderThread();
    GpuTextureView input = target.getColorTextureView();
    if (input == null) {
      return;
    }

    TextureTarget dithered = CbbgDither.renderDitheredTarget(input);
    if (dithered == null) {
      return;
    }

    CAPTURE_DEPTH.set(CAPTURE_DEPTH.get() + 1);
    try {
      // Re-enter vanilla screenshot code, but read from the dithered RGBA8 target instead of the
      // HDR main target (which would otherwise get quantized without dithering during readback).
      Screenshot.takeScreenshot(dithered, downscaleFactor, callback);
      ci.cancel();
    } finally {
      int next = CAPTURE_DEPTH.get() - 1;
      if (next <= 0) {
        CAPTURE_DEPTH.remove();
      } else {
        CAPTURE_DEPTH.set(next);
      }
    }
  }
}
