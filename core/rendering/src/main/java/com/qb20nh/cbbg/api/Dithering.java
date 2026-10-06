package com.qb20nh.cbbg.api;

/** CPU conversion from linear RGBA floats to dithered RGBA8 pixels. */
public final class Dithering {
  private Dithering() {}

  /**
   * Preserves row order and quantizes alpha without noise. RGB follows CBBG's shader. Coordinates
   * use pixel centers; upload the same noise frame for equivalent GPU processing.
   */
  public static byte[] rgba8(
      float[] source, int width, int height, NoiseVolume noise, int frame, DitherOptions options) {
    if (width <= 0 || height <= 0) {
      throw new IllegalArgumentException("Image dimensions must be positive");
    }
    int length = Math.multiplyExact(Math.multiplyExact(width, height), 4);
    if (source.length != length) {
      throw new IllegalArgumentException("Expected four floats per pixel");
    }
    byte[] output = new byte[length];
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        int offset = (y * width + x) * 4;
        int nx = (int) Math.floor((x + 0.5) * options.scaleX());
        int ny = (int) Math.floor((y + 0.5) * options.scaleY());
        int pixel = noise.pixelABGR(nx, ny, frame);
        double strength = options.demo() && x < width / 2 ? 0 : options.strength();
        for (int channel = 0; channel < 4; channel++) {
          float value = source[offset + channel];
          if (!Float.isFinite(value)) {
            throw new IllegalArgumentException("Image components must be finite");
          }
          double amount =
              channel == 3 ? 0 : ((pixel >>> (channel * 8) & 255) / 255.0 - 0.5) * strength;
          int quantized =
              Math.max(0, Math.min(255, (int) Math.floor(value * 255.0 + amount + 0.5)));
          output[offset + channel] =
              (byte)
                  (channel < 3 && options.demo() && x == width / 2 ? 255 - quantized : quantized);
        }
      }
    }
    return output;
  }
}
