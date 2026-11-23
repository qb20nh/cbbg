package com.qb20nh.cbbg.render;

import com.mojang.blaze3d.platform.GLX;
import org.lwjgl.opengl.GL11;

public final class LegacyTextureState {
    private LegacyTextureState() {}

    public static void active(int unit) { GLX.gl13ActiveTexture(unit); }

    public static void bind(int texture) { GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture); }
}
