package com.qb20nh.cbbg.api;

import com.qb20nh.cbbg.math.BlueNoise;

/** Immutable periodic RGB noise. Generation runs on the invoking thread. */
public final class NoiseVolume {
  private final int width;
  private final int height;
  private final int depth;
  private final int[] pixels;

  private NoiseVolume(int width, int height, int depth, int[] pixels) {
    this.width = width;
    this.height = height;
    this.depth = depth;
    this.pixels = pixels;
  }

  /**
   * Generates CBBG's noise without reading settings, accessing caches or allocating GPU resources.
   * Dimensions must be powers of two, with at least two pixels in total. Run this method on an
   * executor for large volumes; interruption cancels generation.
   */
  public static NoiseVolume generate(int width, int height, int depth, long seed) {
    if (!powerOfTwo(width) || !powerOfTwo(height) || !powerOfTwo(depth)) {
      throw new IllegalArgumentException("Noise dimensions must be positive powers of two");
    }
    long count = (long) width * height * depth;
    if (count < 2 || count > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("Noise volume is too small or too large");
    }
    return new NoiseVolume(
        width, height, depth, BlueNoise.generatePixels(width, height, depth, seed));
  }

  private static boolean powerOfTwo(int value) {
    return value > 0 && (value & (value - 1)) == 0;
  }

  /** Identifies deterministic generation output so consumers can invalidate older caches. */
  public static int algorithmVersion() {
    return 3;
  }

  public int width() {
    return width;
  }

  public int height() {
    return height;
  }

  public int depth() {
    return depth;
  }

  /** Samples packed ABGR; coordinates and frame wrap in each dimension. */
  public int pixelABGR(int x, int y, int frame) {
    return pixels[
        (Math.floorMod(frame, depth) * height + Math.floorMod(y, height)) * width
            + Math.floorMod(x, width)];
  }

  /** Returns an independent RGBA byte array for uploading one noise frame to a GPU texture. */
  public byte[] frameRGBA(int frame) {
    byte[] bytes = new byte[Math.multiplyExact(Math.multiplyExact(width, height), 4)];
    int offset = Math.floorMod(frame, depth) * width * height;
    for (int i = 0; i < width * height; i++) {
      int pixel = pixels[offset + i];
      bytes[i * 4] = (byte) pixel;
      bytes[i * 4 + 1] = (byte) (pixel >>> 8);
      bytes[i * 4 + 2] = (byte) (pixel >>> 16);
      bytes[i * 4 + 3] = (byte) 255;
    }
    return bytes;
  }
}
