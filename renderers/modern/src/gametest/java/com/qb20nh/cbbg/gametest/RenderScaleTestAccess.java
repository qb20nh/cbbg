package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.pipeline.RenderTarget;
import java.lang.reflect.Field;
import java.util.Objects;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** Shared reflective access to the pinned RenderScale test fixtures. */
@NullMarked
final class RenderScaleTestAccess {
  private RenderScaleTestAccess() {}

  static float setShaderTestScale(float scale) {
    Object config = Objects.requireNonNull(call(null, "getConfig"));
    float previous = ((Number) Objects.requireNonNull(field(config, "scale"))).floatValue();
    set(config, "scale", scale);
    call(Objects.requireNonNull(call(null, "getInstance")), "onResolutionChanged");
    return previous;
  }

  static void assertShaderTargetFormat(RenderTarget main, String expected) {
    RenderTarget scaled =
        (RenderTarget) field(Objects.requireNonNull(call(null, "getInstance")), "renderTarget");
    if (scaled == null
        || !Objects.requireNonNull(scaled.getColorTexture()).getFormat().name().equals(expected)
        || scaled.width != Math.max(main.width / 2, 1)
        || scaled.height != Math.max(main.height / 2, 1)) {
      throw new AssertionError(
          "RenderScale target did not follow the active shader policy at 0.5x");
    }
  }

  static @Nullable Object field(Object target, String name) {
    try {
      Class<?> type = target instanceof Class<?> value ? value : target.getClass();
      Field field = type.getDeclaredField(name);
      field.setAccessible(true);
      return field.get(target instanceof Class<?> ? null : target);
    } catch (ReflectiveOperationException failure) {
      throw new LinkageError("Could not inspect " + name, failure);
    }
  }

  static void set(Object target, String name, @Nullable Object value) {
    try {
      target.getClass().getField(name).set(target, value);
    } catch (ReflectiveOperationException failure) {
      throw new LinkageError("Could not configure " + name, failure);
    }
  }

  static @Nullable Object call(
      @Nullable Object target, String name, Class<?>[] types, @Nullable Object... args) {
    try {
      Class<?> type =
          target == null ? Class.forName("dev.zelo.renderscale.RenderScale") : target.getClass();
      return type.getMethod(name, types).invoke(target, args);
    } catch (ReflectiveOperationException failure) {
      throw new LinkageError("RenderScale call failed: " + name, failure);
    }
  }

  static @Nullable Object call(@Nullable Object target, String name) {
    return call(target, name, new Class<?>[0]);
  }
}
