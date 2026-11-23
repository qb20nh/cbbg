package com.qb20nh.cbbg.render;

import com.mojang.blaze3d.platform.GlStateManager;

public final class LegacyTextureState {
    private LegacyTextureState() {}
    public static void active(int unit) { GlStateManager.activeTexture(unit); }
    public static void bind(int texture) { GlStateManager.bindTexture(texture); }
}
