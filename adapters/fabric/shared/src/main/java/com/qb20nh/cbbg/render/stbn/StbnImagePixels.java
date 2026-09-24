package com.qb20nh.cbbg.render.stbn;

import com.mojang.blaze3d.platform.NativeImage;

/** Version-specific NativeImage color representation. */
public final class StbnImagePixels {
    private StbnImagePixels() {}

    public static void set(NativeImage image, int x, int y, int color) {
        image.setPixel(x, y, color);
    }
}
