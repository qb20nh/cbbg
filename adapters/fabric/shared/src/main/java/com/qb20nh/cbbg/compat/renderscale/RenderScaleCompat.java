package com.qb20nh.cbbg.compat.renderscale;

import java.lang.reflect.Method;
import java.util.function.Supplier;
import com.qb20nh.cbbg.platform.LoaderPlatform;

/**
 * Lightweight compatibility helpers for the RenderScale mod.
 *
 * <p>
 * RenderScale renders the world into intermediate targets before blitting to Minecraft's main
 * target. Older versions identify these by label; RenderPearl versions use {@code MainTarget}.
 * Renderer adapters preserve float precision, while this helper supplies the effective pixel grid.
 */
public final class RenderScaleCompat {

    private static final boolean RENDER_SCALE_LOADED =
            LoaderPlatform.isModLoaded("renderscale");

    private static final String RENDER_SCALE_COLOR_LABEL = "RenderScale / Color";

    private static volatile boolean scaleReflectionInitialized = false;
    private static volatile boolean scaleReflectionFailed = false;
    private static volatile Method commonGetConfig;
    private static volatile Method configGetScale;
    private static volatile Method rendererGetInstance;
    private static volatile Method rendererGetScale;

    private RenderScaleCompat() {}

    public static boolean isLoaded() {
        return RENDER_SCALE_LOADED;
    }

    public static boolean isRenderScaleColorTextureLabel(Supplier<String> label) {
        if (!RENDER_SCALE_LOADED) {
            return false;
        }
        return RENDER_SCALE_COLOR_LABEL.equals(label.get());
    }

    /**
     * Returns a coordinate scale used to make dithering operate on RenderScale's internal pixel
     * grid. This is only meaningful for downscaling; for {@code scale >= 1} this returns {@code 1}.
     */
    public static float getDitherCoordScale() {
        if (!RENDER_SCALE_LOADED) {
            return 1.0F;
        }

        float scale = tryGetRenderScale();
        if (!(scale > 0.0F) || scale >= 1.0F) {
            return 1.0F;
        }
        return scale;
    }

    private static float tryGetRenderScale() {
        ensureScaleReflection();
        if (scaleReflectionFailed) {
            return 1.0F;
        }

        try {
            Object renderer = rendererGetInstance == null ? null : rendererGetInstance.invoke(null);
            Object scale = renderer == null ? configGetScale.invoke(commonGetConfig.invoke(null))
                    : rendererGetScale.invoke(renderer);
            if (scale instanceof Number n) {
                return n.floatValue();
            }
            return 1.0F;
        } catch (Exception e) {
            // If RenderScale changes its API, fall back to vanilla behavior.
            scaleReflectionFailed = true;
            return 1.0F;
        }
    }

    private static void ensureScaleReflection() {
        if (scaleReflectionInitialized || scaleReflectionFailed || !RENDER_SCALE_LOADED) {
            return;
        }
        synchronized (RenderScaleCompat.class) {
            if (scaleReflectionInitialized || scaleReflectionFailed) {
                return;
            }
            try {
                Class<?> common;
                try {
                    common = Class.forName("dev.zelo.renderscale.CommonClass");
                } catch (ClassNotFoundException newerApi) {
                    common = Class.forName("dev.zelo.renderscale.RenderScale");
                }
                Class<?> config = Class.forName("dev.zelo.renderscale.config.RenderScaleConfig");
                commonGetConfig = common.getMethod("getConfig");
                configGetScale = config.getMethod("getScale");
                try {
                    Class<?> renderer = Class.forName("dev.zelo.renderscale.RenderScale");
                    rendererGetScale = renderer.getMethod("getRenderScaleFactor");
                    rendererGetInstance = renderer.getMethod("getInstance");
                } catch (ClassNotFoundException | NoSuchMethodException olderApi) {
                    rendererGetInstance = null;
                    rendererGetScale = null;
                }
                scaleReflectionInitialized = true;
            } catch (Exception e) {
                scaleReflectionFailed = true;
            }
        }
    }
}
