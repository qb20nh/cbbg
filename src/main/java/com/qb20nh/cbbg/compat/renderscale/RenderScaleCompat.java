package com.qb20nh.cbbg.compat.renderscale;

import java.lang.reflect.Method;
import java.util.function.Supplier;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Lightweight compatibility helpers for the RenderScale mod.
 *
 * <p>
 * RenderScale renders the world into its own {@code TextureTarget} labelled {@code "RenderScale"}
 * and then blits that back into Minecraft's main render target. To avoid early quantization, cbbg
 * upgrades RenderScale's intermediate color target to a float internal format when active.
 */
public final class RenderScaleCompat {

    private static final boolean RENDER_SCALE_LOADED =
            FabricLoader.getInstance().isModLoaded("renderscale");

    private static final String RENDER_SCALE_COLOR_LABEL = "RenderScale / Color";

    private static volatile boolean scaleReflectionInitialized = false;
    private static volatile boolean scaleReflectionFailed = false;
    private static volatile Method commonGetConfig;
    private static volatile Method configGetScale;

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

        float scale = tryGetConfiguredScale();
        if (!(scale > 0.0F) || scale >= 1.0F) {
            return 1.0F;
        }
        return scale;
    }

    private static float tryGetConfiguredScale() {
        ensureScaleReflection();
        if (scaleReflectionFailed) {
            return 1.0F;
        }

        try {
            Object cfg = commonGetConfig.invoke(null);
            Object scale = configGetScale.invoke(cfg);
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
                Class<?> common = Class.forName("dev.zelo.renderscale.CommonClass");
                Class<?> config = Class.forName("dev.zelo.renderscale.config.RenderScaleConfig");
                commonGetConfig = common.getMethod("getConfig");
                configGetScale = config.getMethod("getScale");
                scaleReflectionInitialized = true;
            } catch (Exception e) {
                scaleReflectionFailed = true;
            }
        }
    }
}
