package com.qb20nh.cbbg.mixin;

import com.mojang.blaze3d.pipeline.MainTarget;
import com.qb20nh.cbbg.CbbgClient;
import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.render.MainTargetFormatSupport;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.At;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

@Mixin(MainTarget.class)
public abstract class MainTargetMixin {

    @Inject(method = "allocateColorAttachment", at = @At("HEAD"), cancellable = true)
    private void cbbg$allocateColorAttachment(@Coerce Object dimension,
            CallbackInfoReturnable<Boolean> cir) {
        MainTargetDimensionAccessor dim = (MainTargetDimensionAccessor) dimension;
        int width = dim.cbbg$getWidth();
        int height = dim.cbbg$getHeight();

        // Vanilla behavior when cbbg is not active.
        if (!CbbgClient.isEnabled()) {
            return;
        }

        // Decide the effective float format for this session.
        CbbgConfig.PixelFormat requested = CbbgConfig.get().pixelFormat();
        CbbgConfig.PixelFormat effective = MainTargetFormatSupport.getEffective(requested);
        if (effective == CbbgConfig.PixelFormat.RGBA8) {
            return;
        }

        RenderSystem.assertOnRenderThreadOrInit();

        // Try to allocate float at full resolution; if it fails, fall back to RGBA8 at the same
        // resolution (so we don't trigger vanilla's smaller-dimension fallback unless absolutely
        // necessary).
        if (tryAllocateColor(width, height, effective)) {
            cir.setReturnValue(true);
            return;
        }

        MainTargetFormatSupport.disable(effective, null);

        // Retry once with next-best float (32F -> 16F), otherwise fall back to RGBA8.
        CbbgConfig.PixelFormat fallback = MainTargetFormatSupport.getEffective(requested);
        if (fallback != CbbgConfig.PixelFormat.RGBA8 && tryAllocateColor(width, height, fallback)) {
            cir.setReturnValue(true);
            return;
        }
        if (fallback != CbbgConfig.PixelFormat.RGBA8) {
            MainTargetFormatSupport.disable(fallback, null);
        }

        cir.setReturnValue(tryAllocateRgba8(width, height));
    }

    private boolean tryAllocateColor(int width, int height, CbbgConfig.PixelFormat format) {
        int internal = switch (format) {
            case RGBA16F -> GL30.GL_RGBA16F;
            case RGBA32F -> GL30.GL_RGBA32F;
            case RGBA8 -> GL11.GL_RGBA8;
        };
        return tryAllocate(width, height, internal, GL11.GL_FLOAT);
    }

    private boolean tryAllocateRgba8(int width, int height) {
        return tryAllocate(width, height, GL11.GL_RGBA8, GL11.GL_UNSIGNED_BYTE);
    }

    private boolean tryAllocate(int width, int height, int internalFormat, int type) {
        GlStateManager._getError();
        GlStateManager._bindTexture(((MainTarget) (Object) this).getColorTextureId());
        GlStateManager._texImage2D(GL11.GL_TEXTURE_2D, 0, internalFormat, width, height, 0,
                GL11.GL_RGBA, type, null);
        return GlStateManager._getError() == GL11.GL_NO_ERROR;
    }
}
