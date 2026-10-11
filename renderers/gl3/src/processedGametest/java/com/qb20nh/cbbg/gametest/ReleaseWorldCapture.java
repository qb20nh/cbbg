package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.client.Screenshot;
import org.jspecify.annotations.NullMarked;
import org.lwjgl.opengl.GL11;

@NullMarked
final class ReleaseWorldCapture {
  private ReleaseWorldCapture() {}

  static String format(RenderTarget target) {
    return RenderScaleTargetFormat.name(target).toLowerCase(Locale.ROOT);
  }

  static int scaledDimension(int size, float scale) {
    return Math.max((int) Math.ceil(size * (double) scale), 1);
  }

  static CompletableFuture<byte[]> source(RenderTarget target, boolean floating) {
    return CompletableFuture.completedFuture(
        ReleaseWorldReadback.read(
            target.getColorTextureId(),
            target.width * target.height * (floating ? 16 : 4),
            floating));
  }

  static boolean noiseReady() {
    return GL11.glIsTexture(ReleaseWorldTarget.noise());
  }

  static CompletableFuture<byte[]> noise(boolean floating) {
    if (!floating) return CompletableFuture.completedFuture(new byte[0]);
    int noise = ReleaseWorldTarget.noise();
    if (!GL11.glIsTexture(noise))
      throw new AssertionError("World screenshot has no presented noise");
    int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
    try {
      GlStateManager._bindTexture(noise);
      int width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
      int height = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
      return CompletableFuture.completedFuture(
          ReleaseWorldReadback.read(noise, width * height * 4, false));
    } finally {
      GlStateManager._bindTexture(previous);
    }
  }

  static void screenshot(RenderTarget target, Consumer<NativeImage> callback) {
    int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
    try {
      NativeImage image = ReleaseWorldReadback.packed(() -> Screenshot.takeScreenshot(target));
      callback.accept(image);
    } finally {
      GlStateManager._bindTexture(previous);
    }
  }
}
