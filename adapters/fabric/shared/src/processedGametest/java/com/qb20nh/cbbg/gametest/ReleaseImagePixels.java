package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.platform.NativeImage;
import org.jspecify.annotations.NullMarked;

@NullMarked
final class ReleaseImagePixels {
  private ReleaseImagePixels() {}

  static int argb(NativeImage image, int x, int y) {
    return image.getPixel(x, y);
  }
}
