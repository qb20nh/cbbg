package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.client.Screenshot;
import org.jspecify.annotations.NullMarked;

@NullMarked
final class ReleaseWorldCapture {
  private ReleaseWorldCapture() {}

  static String format(RenderTarget target) {
    return ReleaseAllocationFormat.actual(Objects.requireNonNull(target.getColorTexture()));
  }

  static int scaledDimension(int size, float scale) {
    return Math.max((int) (size * (double) scale), 1);
  }

  static CompletableFuture<byte[]> source(RenderTarget target, boolean floating) {
    return ReleaseWorldReadback.read(
        Objects.requireNonNull(target.getColorTexture()),
        target.width * target.height * (floating ? 16 : 4),
        floating);
  }

  static boolean noiseReady() {
    return ReleaseWorldTarget.noise() != null;
  }

  static CompletableFuture<byte[]> noise(boolean floating) {
    if (!floating) return CompletableFuture.completedFuture(new byte[0]);
    var noise = ReleaseWorldTarget.noise();
    if (noise == null) throw new AssertionError("World screenshot has no presented noise");
    return ReleaseWorldReadback.read(noise, noise.getWidth(0) * noise.getHeight(0) * 4, false);
  }

  static void screenshot(RenderTarget target, Consumer<NativeImage> callback) {
    Screenshot.takeScreenshot(target, callback);
  }
}
