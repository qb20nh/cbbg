package com.qb20nh.cbbg.render;

import com.qb20nh.cbbg.config.CbbgConfig.PixelFormat;
import org.jspecify.annotations.Nullable;

/** Scoped allocation policy for vanilla blur and transparency intermediates. */
public final class MenuBlurGuard {
  private static final ThreadLocal<PixelFormat> FORMAT = new ThreadLocal<>();

  private MenuBlurGuard() {}

  public static @Nullable PixelFormat getActiveFormat() {
    return FORMAT.get();
  }

  public static void set(@Nullable PixelFormat format) {
    if (format == null) FORMAT.remove();
    else FORMAT.set(format);
  }
}
