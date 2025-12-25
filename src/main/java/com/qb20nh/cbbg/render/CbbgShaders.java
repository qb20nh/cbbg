package com.qb20nh.cbbg.render;

import net.minecraft.client.renderer.ShaderInstance;

/**
 * Central storage for cbbg's shader instances on Minecraft 1.21.1.
 *
 * <p>
 * In 1.21.1, shaders are managed by {@link net.minecraft.client.renderer.GameRenderer} and are
 * loaded from the {@code minecraft} namespace. A mixin is responsible for populating these fields
 * during shader reloads.
 */
public final class CbbgShaders {

    private static volatile ShaderInstance dither;
    private static volatile ShaderInstance demo;

    private CbbgShaders() {}

    public static ShaderInstance getDither() {
        return dither;
    }

    public static ShaderInstance getDemo() {
        return demo;
    }

    public static boolean areReady() {
        return dither != null && demo != null;
    }

    public static void setDither(ShaderInstance shader) {
        dither = shader;
    }

    public static void setDemo(ShaderInstance shader) {
        demo = shader;
    }

    public static void clear() {
        dither = null;
        demo = null;
    }
}
