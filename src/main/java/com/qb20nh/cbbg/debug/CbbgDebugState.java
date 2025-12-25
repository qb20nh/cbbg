package com.qb20nh.cbbg.debug;

public final class CbbgDebugState {
    private static volatile int mainInternalFormat = -1;
    private static volatile int lightmapInternalFormat = -1;
    private static volatile int defaultFbEncoding = -1;
    private static volatile boolean framebufferSrgb = false;
    private static volatile long lastUpdateNanos = 0L;

    private static volatile int blurInternalFormat = -1;
    private static volatile long blurLastUpdateNanos = 0L;

    private static volatile int renderScaleInternalFormat = -1;
    private static volatile long renderScaleLastUpdateNanos = 0L;

    private static volatile boolean lastPresentUsedCbbg = false;
    private static volatile long presentLastUpdateNanos = 0L;

    private CbbgDebugState() {}

    public static void clear() {
        mainInternalFormat = -1;
        lightmapInternalFormat = -1;
        defaultFbEncoding = -1;
        framebufferSrgb = false;
        lastUpdateNanos = 0L;

        blurInternalFormat = -1;
        blurLastUpdateNanos = 0L;

        renderScaleInternalFormat = -1;
        renderScaleLastUpdateNanos = 0L;

        lastPresentUsedCbbg = false;
        presentLastUpdateNanos = 0L;
    }

    public static void update(int mainInternal, Integer lightmapInternal, int encoding,
            boolean fbSrgb) {
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

    public static void updateBlurInternalFormat(int internalFormat) {
        blurInternalFormat = internalFormat;
        blurLastUpdateNanos = System.nanoTime();
    }

    public static int getBlurInternalFormat() {
        return blurInternalFormat;
    }

    public static long getBlurLastUpdateNanos() {
        return blurLastUpdateNanos;
    }

    public static void updateRenderScaleInternalFormat(int internalFormat) {
        renderScaleInternalFormat = internalFormat;
        renderScaleLastUpdateNanos = System.nanoTime();
    }

    public static int getRenderScaleInternalFormat() {
        return renderScaleInternalFormat;
    }

    public static long getRenderScaleLastUpdateNanos() {
        return renderScaleLastUpdateNanos;
    }

    public static void updatePresentUsedCbbg(boolean usedCbbg) {
        lastPresentUsedCbbg = usedCbbg;
        presentLastUpdateNanos = System.nanoTime();
    }

    public static boolean wasLastPresentUsedCbbg() {
        return lastPresentUsedCbbg;
    }

    public static long getPresentLastUpdateNanos() {
        return presentLastUpdateNanos;
    }
}
