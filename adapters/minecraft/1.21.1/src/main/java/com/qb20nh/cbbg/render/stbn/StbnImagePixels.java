package com.qb20nh.cbbg.render.stbn;

import com.mojang.blaze3d.platform.NativeImage;
import org.jspecify.annotations.NullMarked;

@NullMarked
public final class StbnImagePixels {
  private StbnImagePixels() {}

  public static void set(NativeImage image, int x, int y, int color) {
    int abgr = (color & 0xff00ff00) | (color & 255) << 16 | (color >>> 16 & 255);
    image.setPixelRGBA(x, y, abgr);
  }
}
