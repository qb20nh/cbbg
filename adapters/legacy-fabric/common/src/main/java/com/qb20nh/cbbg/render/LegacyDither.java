package com.qb20nh.cbbg.render;

import com.qb20nh.cbbg.LegacyNotifications;
import net.fabricmc.loader.api.FabricLoader;

/** Legacy Fabric services for the shared GL2 presentation lifecycle. */
public final class LegacyDither {
    private static final Gl2Dither RENDERER = new Gl2Dither(
            FabricLoader.getInstance().getGameDir().resolve(".cbbg"),
            LegacyTextureState::active, LegacyTextureState::bind, LegacyNotifications::generation);

    private LegacyDither() {}
    public static Gl2Dither renderer() { return RENDERER; }

    public static void tick() { RENDERER.tick(); }
    public static void reload() { RENDERER.reload(); }
    public static void regenerate() { RENDERER.regenerate(); }
    public static void present(int width, Runnable draw) { RENDERER.present(width, draw); }
    public static boolean isReady() { return RENDERER.isReady(); }
    public static boolean hasFailed() { return RENDERER.hasFailed(); }
    public static Gl2NoiseTextures textures() { return RENDERER.textures(); }
    public static long presentations() { return RENDERER.presentations(); }
    public static void resourcesReloaded() { RENDERER.resourcesReloaded(); }
    public static void close() { RENDERER.close(); }
}
