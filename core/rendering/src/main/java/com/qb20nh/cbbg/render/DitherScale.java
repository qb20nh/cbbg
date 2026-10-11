package com.qb20nh.cbbg.render;

/** Maps an output dimension to RenderScale's rounded internal dimension. */
public final class DitherScale {
  private DitherScale() {}

  public static float forDimension(double scale, int size) {
    if (!(scale > 0) || scale >= 1) return 1;
    return (float) Math.max((int) (size * scale), 1) / size;
  }
}
