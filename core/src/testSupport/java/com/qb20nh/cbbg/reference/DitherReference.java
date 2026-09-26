package com.qb20nh.cbbg.reference;

/** CPU pixel oracle. Coordinates use the framebuffer's bottom-left origin. */
public final class DitherReference {
  private DitherReference() {}

  public static int noiseCoordinate(int pixel, double scale, int tileSize) {
    return Math.floorMod((int) Math.floor(pixel * scale), tileSize);
  }

  /** Quantized RGB channel; alpha is preserved by the shader and handled by the readback path. */
  public static int channel(
      double source, int noiseByte, double strength, int x, int width, boolean demo) {
    double amount = demo && x < width / 2 ? 0 : strength;
    double clamped = Math.max(0, Math.min(1, source));
    int quantized = (int) Math.floor(clamped * 255 + (noiseByte / 255.0 - 0.5) * amount + 0.5);
    quantized = Math.max(0, Math.min(255, quantized));
    return demo && x == width / 2 ? 255 - quantized : quantized;
  }
}
