package com.qb20nh.cbbg.render;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** Owns the window shader parameters on the render thread for either Vulkan backend. */
@NullMarked
final class VulkanWindowUniform<C, B> {
  @FunctionalInterface
  interface Upload<C, B> {
    B apply(C commands, @Nullable B buffer, ByteBuffer data);
  }

  private final Function<C, ByteBuffer> allocate;
  private final Upload<C, B> upload;
  private final Consumer<B> release;
  private @Nullable B buffer;
  private float previousStrength;
  private float previousScaleX;
  private float previousScaleY;
  private int previousNoiseSize;
  private int previousWidth;
  private int previousHeight;

  VulkanWindowUniform(Function<C, ByteBuffer> allocate, Upload<C, B> upload, Consumer<B> release) {
    this.allocate = allocate;
    this.upload = upload;
    this.release = release;
  }

  B get(
      C commands,
      float strength,
      float scaleX,
      float scaleY,
      int noiseSize,
      int width,
      int height) {
    B current = buffer;
    if (current == null || !matches(strength, scaleX, scaleY, noiseSize, width, height)) {
      ByteBuffer data = allocate.apply(commands).order(ByteOrder.nativeOrder());
      data.putFloat(0, strength)
          .putFloat(4, 1.0f / noiseSize)
          .putFloat(8, scaleX)
          .putFloat(12, scaleY)
          .putFloat(16, width)
          .putFloat(20, height)
          .putFloat(24, 0)
          .putFloat(28, 0);
      current = upload.apply(commands, current, data);
      buffer = current;
      previousStrength = strength;
      previousScaleX = scaleX;
      previousScaleY = scaleY;
      previousNoiseSize = noiseSize;
      previousWidth = width;
      previousHeight = height;
    }
    return current;
  }

  private boolean matches(
      float strength, float scaleX, float scaleY, int noiseSize, int width, int height) {
    return Float.floatToRawIntBits(strength) == Float.floatToRawIntBits(previousStrength)
        && Float.floatToRawIntBits(scaleX) == Float.floatToRawIntBits(previousScaleX)
        && Float.floatToRawIntBits(scaleY) == Float.floatToRawIntBits(previousScaleY)
        && noiseSize == previousNoiseSize
        && width == previousWidth
        && height == previousHeight;
  }

  void close() {
    B previous = buffer;
    buffer = null;
    if (previous != null) release.accept(previous);
  }
}
