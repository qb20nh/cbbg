package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.platform.NativeImage;
import org.jspecify.annotations.NullMarked;

@NullMarked
final class ReleaseImagePixels {
  private ReleaseImagePixels() {}

  static int argb(NativeImage image, int x, int y) {
    int abgr = image.getPixelRGBA(x, y);
    return (abgr & 0xff00ff00) | (abgr & 255) << 16 | (abgr >>> 16 & 255);
  }
}
