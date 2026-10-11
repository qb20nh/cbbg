package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.platform.NativeImage;
import org.jspecify.annotations.NullMarked;

@NullMarked
final class ReleaseImagePixels {
  private ReleaseImagePixels() {}

  static int argb(NativeImage image, int x, int y) {
    return swapRedBlue(image.getPixelRGBA(x, y));
  }

  static int[] pixels(NativeImage image) {
    int[] pixels = new int[image.getWidth() * image.getHeight()];
    for (int y = 0; y < image.getHeight(); y++) {
      for (int x = 0; x < image.getWidth(); x++) {
        pixels[y * image.getWidth() + x] = argb(image, x, y);
      }
    }
    return pixels;
  }

  static void setArgb(NativeImage image, int x, int y, int argb) {
    image.setPixelRGBA(x, y, swapRedBlue(argb));
  }

  private static int swapRedBlue(int pixel) {
    return (pixel & 0xff00ff00) | (pixel & 255) << 16 | (pixel >>> 16 & 255);
  }
}
