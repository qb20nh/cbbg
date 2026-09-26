package com.qb20nh.cbbg.render;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Thread-local guard used to scope OpenGL format overrides to a specific texture allocation call.
 *
 * <p>This is used so we only upgrade the <em>main</em> render target color attachment to higher
 * precision without affecting other textures.
 */
@NullMarked
public final class GlFormatOverride {

  private static final ThreadLocal<Integer> MAIN_TARGET_COLOR_DEPTH =
      ThreadLocal.withInitial(() -> 0);

  private static final ThreadLocal<@Nullable Integer> FORCED_FORMAT =
      ThreadLocal.withInitial(() -> null);

  private GlFormatOverride() {}

  public static void pushMainTargetColor() {
    MAIN_TARGET_COLOR_DEPTH.set(MAIN_TARGET_COLOR_DEPTH.get() + 1);
  }

  public static void popMainTargetColor() {
    final int next = MAIN_TARGET_COLOR_DEPTH.get() - 1;
    if (next <= 0) {
      MAIN_TARGET_COLOR_DEPTH.remove();
    } else {
      MAIN_TARGET_COLOR_DEPTH.set(next);
    }
  }

  public static boolean isMainTargetColor() {
    return MAIN_TARGET_COLOR_DEPTH.get() > 0;
  }

  public static void pushFormat(@Nullable Integer format) {
    FORCED_FORMAT.set(format);
  }

  public static void popFormat() {
    FORCED_FORMAT.remove();
  }

  public static @Nullable Integer getFormat() {
    return FORCED_FORMAT.get();
  }

  public static void setForcedFormat(@Nullable Integer format) {
    if (format == null) {
      FORCED_FORMAT.remove();
    } else {
      FORCED_FORMAT.set(format);
    }
  }

  public static @Nullable Integer getForcedFormat() {
    return FORCED_FORMAT.get();
  }
}
