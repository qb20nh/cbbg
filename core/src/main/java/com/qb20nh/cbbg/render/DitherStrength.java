package com.qb20nh.cbbg.render;

/** Shared menu-blur compensation; changes only the shader's strength uniform. */
public final class DitherStrength {
    private DitherStrength() {}

    public static float effective(float base, boolean menuScreen, int blurRadius) {
        if (!menuScreen || blurRadius < 1) {
            return base;
        }
        float boosted = base * (1.0f + blurRadius / 5.0f);
        return Math.min(4.0f, Math.max(0.5f, boosted));
    }
}
