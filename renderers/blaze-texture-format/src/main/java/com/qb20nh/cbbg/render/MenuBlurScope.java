package com.qb20nh.cbbg.render;

import com.qb20nh.cbbg.config.CbbgConfig.PixelFormat;
import java.util.ArrayDeque;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
public final class MenuBlurScope {
  private static final ThreadLocal<ArrayDeque<PixelFormat>> FORMATS =
      ThreadLocal.withInitial(ArrayDeque::new);

  private MenuBlurScope() {}

  public static @Nullable PixelFormat format() {
    return FORMATS.get().peek();
  }

  public static void push(PixelFormat format) {
    FORMATS.get().push(format);
  }

  public static void pop() {
    ArrayDeque<PixelFormat> formats = FORMATS.get();
    formats.pop();
    if (formats.isEmpty()) FORMATS.remove();
  }
}
