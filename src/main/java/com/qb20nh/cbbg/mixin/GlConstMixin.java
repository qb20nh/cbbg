package com.qb20nh.cbbg.mixin;

import com.mojang.blaze3d.opengl.GlConst;
import com.mojang.blaze3d.textures.TextureFormat;
import com.qb20nh.cbbg.CbbgClient;
import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.render.GlFormatOverride;
import com.qb20nh.cbbg.render.MainTargetFormatSupport;
import org.lwjgl.opengl.GL30;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GlConst.class)
public abstract class GlConstMixin {

    private GlConstMixin() {}

    private static final int GL_RGBA16F = GL30.GL_RGBA16F;
    private static final int GL_RGBA32F = GL30.GL_RGBA32F;

    @Inject(method = "toGlInternalId", at = @At("HEAD"), cancellable = true)
    private static void cbbg$toGlInternalId(TextureFormat textureFormat,
            CallbackInfoReturnable<Integer> cir) {
        // 1. Explicit override (takes precedence)
        Integer forced = GlFormatOverride.getForcedFormat();
        if (forced != null) {
            cir.setReturnValue(forced);
            return;
        }

        // 2. Main target override (legacy behavior check)
        if (!CbbgClient.isEnabled() || !GlFormatOverride.isMainTargetColor()) {
            return;
        }

        if (textureFormat == TextureFormat.RGBA8) {
            CbbgConfig.PixelFormat fmt =
                    MainTargetFormatSupport.getEffective(CbbgConfig.get().pixelFormat());
            switch (fmt) {
                case RGBA16F -> cir.setReturnValue(GL_RGBA16F);
                case RGBA32F -> cir.setReturnValue(GL_RGBA32F);
                case RGBA8 -> {
                    // Vanilla path (no override)
                }
            }
        }
    }
}
