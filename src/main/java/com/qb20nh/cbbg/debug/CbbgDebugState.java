package com.qb20nh.cbbg.debug;

import org.jspecify.annotations.Nullable;

public final class CbbgDebugState {
  private static volatile int mainInternalFormat = -1;
  private static volatile int lightmapInternalFormat = -1;
  private static volatile int defaultFbEncoding = -1;
  private static volatile boolean framebufferSrgb = false;
  private static volatile long lastUpdateNanos = 0L;

  private CbbgDebugState() {
  }

  public static void clear() {
    mainInternalFormat = -1;
    lightmapInternalFormat = -1;
    defaultFbEncoding = -1;
    framebufferSrgb = false;
    lastUpdateNanos = 0L;
  }

  public static void update(
      int mainInternal, @Nullable Integer lightmapInternal, int encoding, boolean fbSrgb) {
    mainInternalFormat = mainInternal;
    lightmapInternalFormat = lightmapInternal == null ? -1 : lightmapInternal;
    defaultFbEncoding = encoding;
    framebufferSrgb = fbSrgb;
    lastUpdateNanos = System.nanoTime();
  }

  public static int getMainInternalFormat() {
    return mainInternalFormat;
  }

  public static int getLightmapInternalFormat() {
    return lightmapInternalFormat;
  }

  public static int getDefaultFbEncoding() {
    return defaultFbEncoding;
  }

  public static boolean isFramebufferSrgb() {
    return framebufferSrgb;
  }

  public static long getLastUpdateNanos() {
    return lastUpdateNanos;
  }
}
