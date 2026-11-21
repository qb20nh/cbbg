package com.qb20nh.cbbg.render.stbn;

import com.mojang.blaze3d.platform.NativeImage;

/** Preserve the modern loader's ARGB input when using the older ABGR image API. */
public final class StbnImagePixels {
  private StbnImagePixels() {}

  public static void set(NativeImage image, int x, int y, int color) {
    int abgr = (color & 0xff00ff00) | ((color >>> 16) & 0xff) | ((color & 0xff) << 16);
    image.setPixelRGBA(x, y, abgr);
  }
}
