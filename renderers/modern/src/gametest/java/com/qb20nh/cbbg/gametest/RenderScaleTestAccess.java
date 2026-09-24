package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.pipeline.RenderTarget;
import java.lang.reflect.Field;

/** Shared reflective access to the pinned RenderScale test fixtures. */
final class RenderScaleTestAccess {
    private RenderScaleTestAccess() {}

    static float setShaderTestScale(float scale) {
        Object config = call(null, "getConfig");
        float previous = ((Number) field(config, "scale")).floatValue();
        set(config, "scale", scale);
        call(call(null, "getInstance"), "onResolutionChanged");
        return previous;
    }

    static void assertShaderTargetFormat(RenderTarget main, String expected) {
        RenderTarget scaled = (RenderTarget) field(call(null, "getInstance"), "renderTarget");
        if (scaled == null || !scaled.getColorTexture().getFormat().name().equals(expected)
                || scaled.width != Math.max(main.width / 2, 1)
                || scaled.height != Math.max(main.height / 2, 1)) {
            throw new AssertionError("RenderScale target did not follow the active shader policy at 0.5x");
        }
    }

    static Object field(Object target, String name) {
        try {
            Class<?> type = target instanceof Class<?> value ? value : target.getClass();
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target instanceof Class<?> ? null : target);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Could not inspect " + name, failure);
        }
    }

    static void set(Object target, String name, Object value) {
        try {
            target.getClass().getField(name).set(target, value);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Could not configure " + name, failure);
        }
    }

    static Object call(Object target, String name, Class<?>[] types, Object... args) {
        try {
            Class<?> type = target == null ? Class.forName("dev.zelo.renderscale.RenderScale") : target.getClass();
            return type.getMethod(name, types).invoke(target, args);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("RenderScale call failed: " + name, failure);
        }
    }

    static Object call(Object target, String name) {
        return call(target, name, new Class<?>[0]);
    }
}
