package com.qb20nh.cbbg.banding;

import java.util.function.Supplier;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** Session-only state, accessed on the client render thread. */
@NullMarked
public final class BandingDebug {
  // The order of the enabled layouts and views matches SIDE and VIEW in the shader.
  private static final String[] LAYOUTS = {"off", "split", "before", "after"};
  private static final String[] VIEWS = {"rgb", "contrast", "contours"};
  private static int layout;
  private static int view;
  private static boolean presenting;
  private static Runnable closeShader = () -> {};

  private BandingDebug() {}

  public static Component layoutLabel() {
    return Component.translatable(
        "cbbg.banding.layout", Component.translatable("cbbg.banding." + LAYOUTS[layout]));
  }

  public static Component viewLabel() {
    return Component.translatable("cbbg.banding." + VIEWS[view]);
  }

  public static void cycleLayout() {
    layout = (layout + 1) % LAYOUTS.length;
    closeShader.run();
  }

  public static void cycleView() {
    view = (view + 1) % VIEWS.length;
    closeShader.run();
  }

  public static void closeShaderWith(Runnable close) {
    closeShader = close;
  }

  public static <T extends @Nullable Object> T whilePresenting(boolean advance, Supplier<T> draw) {
    boolean previous = presenting;
    presenting = advance;
    try {
      return draw.get();
    } finally {
      presenting = previous;
    }
  }

  public static @Nullable String shader() {
    if (!presenting || layout == 0) return null;
    return "cbbg_banding_" + view + "_" + (layout - 1);
  }
}
