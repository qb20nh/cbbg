package com.qb20nh.cbbg.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.Screenshot;
import org.jspecify.annotations.NullMarked;

/** Owns the temporary target until screenshot readback finishes. */
@NullMarked
public final class Rgba8Capture {
  private Rgba8Capture() {}

  public static void capture(
      RenderTarget source,
      int downscaleFactor,
      Consumer<NativeImage> callback,
      Supplier<TextureTarget> createOutput,
      Consumer<TextureTarget> blit) {
    RenderSystem.assertOnRenderThread();
    if (downscaleFactor <= 0
        || source.width % downscaleFactor != 0
        || source.height % downscaleFactor != 0) {
      throw new IllegalArgumentException("Image size is not divisible by downscale factor");
    }
    if (source.getColorTextureView() == null) {
      throw new IllegalStateException("Tried to capture screenshot of an incomplete framebuffer");
    }
    TextureTarget output = createOutput.get();
    try {
      blit.accept(output);
      Screenshot.takeScreenshot(
          output,
          downscaleFactor,
          image -> {
            try {
              callback.accept(image);
            } finally {
              output.destroyBuffers();
            }
          });
    } catch (RuntimeException | Error failure) {
      output.destroyBuffers();
      throw failure;
    }
  }
}
