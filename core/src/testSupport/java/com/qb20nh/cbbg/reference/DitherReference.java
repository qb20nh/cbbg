package com.qb20nh.cbbg.reference;

import org.jspecify.annotations.NullMarked;

/** CPU pixel oracle. Coordinates use the framebuffer's bottom-left origin. */
@NullMarked
public final class DitherReference {
  private DitherReference() {}

  public static int noiseCoordinate(int pixel, double scale, int tileSize) {
    return Math.floorMod((int) Math.floor((pixel + 0.5) * scale), tileSize);
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

  /** Quantize with the shader's FP32 operations; source is the stored texture sample. */
  public static int shaderChannel(
      double source, int noiseByte, double strength, int x, int width, boolean demo) {
    float amount = demo && x < width / 2 ? 0f : (float) strength;
    float clamped = Math.max(0f, Math.min(1f, (float) source));
    float noise = noiseByte / 255f;
    float scaled = clamped * 255f + (noise - 0.5f) * amount;
    int quantized = (int) Math.floor(scaled + 0.5f);
    quantized = Math.max(0, Math.min(255, quantized));
    return demo && x == width / 2 ? 255 - quantized : quantized;
  }
}
