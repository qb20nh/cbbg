package com.qb20nh.cbbg.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.qb20nh.cbbg.Cbbg;
import com.qb20nh.cbbg.CbbgClient;
import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.debug.CbbgDebugState;
import com.qb20nh.cbbg.debug.CbbgGlNames;
import com.qb20nh.cbbg.debug.CbbgGlUtil;
import com.qb20nh.cbbg.render.CbbgDither;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RenderTarget.class)
public abstract class RenderTargetBlitToScreenMixin {

    private static final ThreadLocal<Integer> PRESENT_DEPTH = ThreadLocal.withInitial(() -> 0);
    private static final AtomicBoolean loggedOnce = new AtomicBoolean(false);
    private static volatile CbbgConfig.Mode lastMode = null;
    private static volatile CbbgConfig.PixelFormat lastPixelFormat = null;

    @Inject(method = "blitToScreen(IIZ)V", at = @At("HEAD"), cancellable = true)
    private void cbbg$blitToScreen(int width, int height, boolean disableBlend, CallbackInfo ci) {
        // Prevent recursion if we re-enter blitToScreen during our own present path.
        if (PRESENT_DEPTH.get() > 0) {
            return;
        }

        CbbgConfig.Mode modeNow = CbbgClient.getEffectiveMode();
        handleModeTransition(modeNow);
        handlePixelFormatTransition(modeNow);
        if (!modeNow.isActive()) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        RenderTarget main = mc.getMainRenderTarget();
        if ((Object) this != main) {
            return;
        }

        logVerificationOnce(main);

        PRESENT_DEPTH.set(PRESENT_DEPTH.get() + 1);
        try {
            boolean didBlit =
                    modeNow == CbbgConfig.Mode.DEMO ? CbbgDither.blitToScreenWithDemo(main)
                            : CbbgDither.blitToScreenWithDither(main);
            CbbgDebugState.updatePresentUsedCbbg(didBlit);
            if (didBlit) {
                ci.cancel();
            }
        } finally {
            int next = PRESENT_DEPTH.get() - 1;
            if (next <= 0) {
                PRESENT_DEPTH.remove();
            } else {
                PRESENT_DEPTH.set(next);
            }
        }
    }

    private static void handleModeTransition(CbbgConfig.Mode modeNow) {
        CbbgConfig.Mode prev = lastMode;
        if (prev == null) {
            lastMode = modeNow;
            return;
        }
        if (prev == modeNow) {
            return;
        }

        lastMode = modeNow;

        // Log verification again after a toggle, and reset any "disabled due to error" state.
        loggedOnce.set(false);
        CbbgDither.resetAfterToggle();
        if (!modeNow.isActive()) {
            CbbgDebugState.clear();
        }

        // Recreate targets on the next render call boundary so we don't destroy textures mid-blit.
        boolean activeNow = modeNow.isActive();
        boolean activePrev = prev.isActive();
        if (activeNow == activePrev) {
            return;
        }

        RenderSystem.recordRenderCall(() -> {
            Minecraft mc = Minecraft.getInstance();
            mc.getMainRenderTarget().resize(mc.getWindow().getWidth(), mc.getWindow().getHeight(),
                    Minecraft.ON_OSX);
        });
    }

    private static void handlePixelFormatTransition(CbbgConfig.Mode modeNow) {
        CbbgConfig.PixelFormat fmtNow = CbbgConfig.get().pixelFormat();
        CbbgConfig.PixelFormat prev = lastPixelFormat;
        if (prev == null) {
            lastPixelFormat = fmtNow;
            return;
        }
        if (prev == fmtNow) {
            return;
        }

        lastPixelFormat = fmtNow;

        // Re-log verification again after a format change, and reset any "disabled due to error"
        // state so the user can recover by switching formats.
        loggedOnce.set(false);
        CbbgDither.resetAfterToggle();

        // Only matters while cbbg is active; otherwise vanilla RGBA8 is used.
        if (!modeNow.isActive()) {
            return;
        }

        // Recreate targets on the next render call boundary so we don't destroy textures mid-blit.
        RenderSystem.recordRenderCall(() -> {
            Minecraft mc = Minecraft.getInstance();
            mc.getMainRenderTarget().resize(mc.getWindow().getWidth(), mc.getWindow().getHeight(),
                    Minecraft.ON_OSX);
        });
    }

    private static void logVerificationOnce(RenderTarget main) {
        if (!loggedOnce.compareAndSet(false, true)) {
            return;
        }

        try {
            int mainInternal = CbbgGlUtil.getTextureInternalFormat2D(main.getColorTextureId());
            Integer lightmapInternal = swallowExceptions(() -> {
                DynamicTexture lightTexture =
                        ((LightTextureAccessor) (Object) Minecraft.getInstance().gameRenderer
                                .lightTexture()).cbbg$getLightTexture();
                return CbbgGlUtil.getTextureInternalFormat2D(lightTexture.getId());
            });

            // Default framebuffer encoding + SRGB conversion state.
            int prevFbo = GlStateManager._getInteger(GL30.GL_FRAMEBUFFER_BINDING);
            int encoding;
            boolean fbSrgb;
            try {
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
                encoding = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER,
                        GL11.GL_BACK_LEFT, GL30.GL_FRAMEBUFFER_ATTACHMENT_COLOR_ENCODING);
                fbSrgb = GL11.glIsEnabled(GL30.GL_FRAMEBUFFER_SRGB);
            } finally {
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
            }

            CbbgDebugState.update(mainInternal, lightmapInternal, encoding, fbSrgb);

            if (Cbbg.LOGGER.isInfoEnabled()) {
                Cbbg.LOGGER.info(
                        "cbbg verify: MainTarget internalFormat={} Lightmap internalFormat={} DefaultFB encoding={} FRAMEBUFFER_SRGB={}",
                        CbbgGlNames.glInternalName(mainInternal),
                        lightmapInternal == null ? "unknown"
                                : CbbgGlNames.glInternalName(lightmapInternal),
                        CbbgGlNames.glEncodingName(encoding), fbSrgb);
            }
        } catch (Exception e) {
            Cbbg.LOGGER.warn("cbbg verify failed (continuing without verification).", e);
        }
    }

    private static <T> T swallowExceptions(java.util.concurrent.Callable<T> action) {
        try {
            return action.call();
        } catch (Exception ignored) {
            return null;
        }
    }
}
