package com.qb20nh.cbbg.render;

import com.qb20nh.cbbg.config.CbbgConfig.PixelFormat;
import java.util.function.Predicate;

/** Format selection only; adapters own capability probes and allocation failures. */
public final class FormatPolicy {
  private FormatPolicy() {}

  public static PixelFormat effective(PixelFormat requested, Predicate<PixelFormat> supported) {
    if (requested == PixelFormat.RGBA32F && supported.test(PixelFormat.RGBA32F)) {
      return PixelFormat.RGBA32F;
    }
    if ((requested == PixelFormat.RGBA16F || requested == PixelFormat.RGBA32F)
        && supported.test(PixelFormat.RGBA16F)) {
      return PixelFormat.RGBA16F;
    }
    return PixelFormat.RGBA8;
  }
}
