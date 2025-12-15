package com.qb20nh.cbbg.render;

/**
 * Thread-local guard used to scope OpenGL format overrides to a specific texture allocation call.
 *
 * <p>This is used so we only upgrade the <em>main</em> render target color attachment to higher
 * precision without affecting other textures.
 */
public final class GlFormatOverride {

  private static final ThreadLocal<Integer> MAIN_TARGET_COLOR_DEPTH =
      ThreadLocal.withInitial(() -> 0);
  private static final ThreadLocal<Integer> LIGHTMAP_DEPTH = ThreadLocal.withInitial(() -> 0);

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

  public static void pushLightmap() {
    LIGHTMAP_DEPTH.set(LIGHTMAP_DEPTH.get() + 1);
  }

  public static void popLightmap() {
    final int next = LIGHTMAP_DEPTH.get() - 1;
    if (next <= 0) {
      LIGHTMAP_DEPTH.remove();
    } else {
      LIGHTMAP_DEPTH.set(next);
    }
  }

  public static boolean isLightmap() {
    return LIGHTMAP_DEPTH.get() > 0;
  }
}
