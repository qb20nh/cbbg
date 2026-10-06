package com.qb20nh.cbbg.mixin;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.qb20nh.cbbg.CbbgClient;
import com.qb20nh.cbbg.render.CbbgDither;
import com.qb20nh.cbbg.render.Rgba8Readback;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.NullMarked;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Screenshot.class)
@NullMarked
public abstract class ScreenshotMixin {

  private ScreenshotMixin() {}

  @Unique
  private static final ThreadLocal<Integer> CAPTURE_DEPTH = ThreadLocal.withInitial(() -> 0);

  @Inject(
      method =
          "takeScreenshot(Lcom/mojang/blaze3d/pipeline/RenderTarget;ILjava/util/function/Consumer;)V",
      at = @At("HEAD"),
      cancellable = true)
  // Target identity preserves other mods' independently owned screenshot targets.
  @SuppressWarnings("ReferenceEquality")
  private static void cbbg$takeScreenshot(
      RenderTarget target,
      int downscaleFactor,
      @NonNull Consumer<NativeImage> callback,
      CallbackInfo ci) {
    if (CAPTURE_DEPTH.get() > 0) {
      return;
    }
    if (target != Minecraft.getInstance().gameRenderer.mainRenderTarget()) {
      return;
    }

    RenderSystem.assertOnRenderThread();
    GpuTexture color = target.getColorTexture();
    if (color == null) {
      return;
    }

    if (CbbgClient.isEnabled()) {
      GpuTextureView input = target.getColorTextureView();
      if (input != null) {
        TextureTarget output = CbbgDither.renderScreenshotTarget(input);
        if (output != null) {
          CAPTURE_DEPTH.set(CAPTURE_DEPTH.get() + 1);
          try {
            Screenshot.takeScreenshot(output, downscaleFactor, callback);
            ci.cancel();
          } finally {
            int next = CAPTURE_DEPTH.get() - 1;
            if (next <= 0) {
              CAPTURE_DEPTH.remove();
            } else {
              CAPTURE_DEPTH.set(next);
            }
          }
          return;
        }
      }
    }

    if (color.getFormat() == GpuFormat.RGBA16_FLOAT
        || color.getFormat() == GpuFormat.RGBA32_FLOAT) {
      Rgba8Readback.capture(target, downscaleFactor, callback);
      ci.cancel();
    }
  }
}
