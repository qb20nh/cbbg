package com.qb20nh.cbbg.render;

import com.qb20nh.cbbg.config.CbbgConfig.PixelFormat;

/**
 * Thread-local guard for Minecraft's menu blur post chain ({@code minecraft:blur}).
 *
 * <p>
 * Vanilla runs the blur as a post-processing chain during GUI rendering (see
 * {@code GameRenderer#processBlurEffect}). The post chain allocates intermediate
 * {@code RenderTarget}s via {@code TextureTarget(null, ...)} which default to RGBA8.
 *
 * <p>
 * cbbg uses this guard to selectively upgrade those intermediate targets to float formats while the
 * blur chain is executing, avoiding early quantization before our final present-time dither.
 */
public final class MenuBlurGuard {

    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<PixelFormat> ACTIVE_FORMAT = new ThreadLocal<>();

    // Tracks the last format used for blur targets (used to decide when to clear the
    // post-processing
    // resource pool so cached targets are recreated with the correct format).
    private static volatile PixelFormat lastBlurFormat = null;

    private MenuBlurGuard() {}

    public static void push(PixelFormat format) {
        DEPTH.set(DEPTH.get() + 1);
        if (format != null) {
            ACTIVE_FORMAT.set(format);
        }
    }

    public static void pop() {
        int next = DEPTH.get() - 1;
        if (next <= 0) {
            DEPTH.remove();
            ACTIVE_FORMAT.remove();
        } else {
            DEPTH.set(next);
        }
    }

    public static boolean isActive() {
        return DEPTH.get() > 0;
    }

    public static PixelFormat getActiveFormat() {
        return ACTIVE_FORMAT.get();
    }

    /**
     * Updates the remembered blur format. Returns {@code true} if the value changed.
     *
     * <p>
     * This is separate from {@link #isActive()} so the caller can track transitions across frames
     * (e.g. when cbbg is toggled or the pixel format changes).
     */
    public static boolean updateLastBlurFormat(PixelFormat format) {
        PixelFormat prev = lastBlurFormat;
        if (prev == format) {
            return false;
        }
        lastBlurFormat = format;
        return true;
    }
}
