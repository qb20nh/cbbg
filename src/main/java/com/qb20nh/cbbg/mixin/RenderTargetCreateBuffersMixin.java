package com.qb20nh.cbbg.mixin;

import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.qb20nh.cbbg.Cbbg;
import com.qb20nh.cbbg.CbbgClient;
import com.qb20nh.cbbg.compat.renderscale.RenderScaleCompat;
import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.debug.CbbgDebugState;
import com.qb20nh.cbbg.debug.CbbgGlUtil;
import com.qb20nh.cbbg.render.MainTargetFormatSupport;
import com.qb20nh.cbbg.render.MenuBlurGuard;
import java.util.concurrent.atomic.AtomicBoolean;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RenderTarget.class)
public abstract class RenderTargetCreateBuffersMixin {

    private static final AtomicBoolean loggedResizeFloatFailure = new AtomicBoolean(false);
    private static final AtomicBoolean loggedMenuBlurFormatFailure = new AtomicBoolean(false);
    private static final AtomicBoolean loggedRenderScaleFormatFailure = new AtomicBoolean(false);

    @Shadow
    public int width;

    @Shadow
    public int height;

    @Shadow
    public int viewWidth;

    @Shadow
    public int viewHeight;

    @Shadow
    @Final
    public boolean useDepth;

    @Shadow
    public int frameBufferId;

    @Shadow
    protected int colorTextureId;

    @Shadow
    protected int depthBufferId;

    @Shadow
    private void setFilterMode(int filterMode, boolean force) {
        throw new AssertionError("mixin");
    }

    @Inject(method = "createBuffers", at = @At("HEAD"), cancellable = true)
    private void cbbg$createBuffers(int width, int height, boolean clearError, CallbackInfo ci) {
        if (!CbbgClient.isEnabled()) {
            return;
        }

        boolean isMainTarget = ((Object) this instanceof MainTarget);
        boolean isMenuBlurPostChainTarget = !isMainTarget && MenuBlurGuard.isActive();
        boolean isRenderScaleColorTarget = !isMainTarget && !isMenuBlurPostChainTarget
                && isRenderScaleColorTarget(width, height);
        if (!isMainTarget && !isMenuBlurPostChainTarget && !isRenderScaleColorTarget) {
            return;
        }

        CbbgConfig.PixelFormat requested = CbbgConfig.get().pixelFormat();
        CbbgConfig.PixelFormat effective =
                isMenuBlurPostChainTarget ? MenuBlurGuard.getActiveFormat()
                        : MainTargetFormatSupport.getEffective(requested);
        if (effective == null || effective == CbbgConfig.PixelFormat.RGBA8) {
            return;
        }

        ci.cancel();

        RenderSystem.assertOnRenderThreadOrInit();
        int max = RenderSystem.maxSupportedTextureSize();
        if (width <= 0 || width > max || height <= 0 || height > max) {
            throw new IllegalArgumentException("Window " + width + "x" + height
                    + " size out of bounds (max. size: " + max + ")");
        }

        // Mirror vanilla RenderTarget#createBuffers, but allocate the color texture with a float
        // internal format when possible, falling back to RGBA8.
        this.viewWidth = width;
        this.viewHeight = height;
        this.width = width;
        this.height = height;

        this.frameBufferId = GlStateManager.glGenFramebuffers();
        this.colorTextureId = TextureUtil.generateTextureId();

        if (this.useDepth) {
            this.depthBufferId = TextureUtil.generateTextureId();
            GlStateManager._bindTexture(this.depthBufferId);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER,
                    GL11.GL_NEAREST);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER,
                    GL11.GL_NEAREST);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_COMPARE_MODE, 0);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S,
                    GL12.GL_CLAMP_TO_EDGE);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T,
                    GL12.GL_CLAMP_TO_EDGE);
            GlStateManager._texImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_DEPTH_COMPONENT, this.width,
                    this.height, 0, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, null);
        }

        this.setFilterMode(GL11.GL_NEAREST, true);

        GlStateManager._bindTexture(this.colorTextureId);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S,
                GL12.GL_CLAMP_TO_EDGE);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T,
                GL12.GL_CLAMP_TO_EDGE);

        CbbgConfig.PixelFormat usedFormat =
                isMenuBlurPostChainTarget ? allocateMenuBlurColorStorageWithFallback(effective)
                        : isRenderScaleColorTarget
                                ? allocateRenderScaleColorStorageWithFallback(effective)
                                : allocateMainColorStorageWithFallback(requested, effective, null);

        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.frameBufferId);
        GlStateManager._glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, this.colorTextureId, 0);
        if (this.useDepth) {
            GlStateManager._glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                    GL11.GL_TEXTURE_2D, this.depthBufferId, 0);
        }

        try {
            ((RenderTarget) (Object) this).checkStatus();
        } catch (RuntimeException e) {
            // Some drivers accept the allocation but refuse a float renderable attachment.
            // Retry with fallbacks before giving up.
            if (isMenuBlurPostChainTarget) {
                if (loggedMenuBlurFormatFailure.compareAndSet(false, true)) {
                    Cbbg.LOGGER.warn(
                            "Menu blur: failed to allocate float intermediate target; falling back to RGBA8 for blur targets.",
                            e);
                }
                allocateColorStorage(CbbgConfig.PixelFormat.RGBA8);
            } else if (isRenderScaleColorTarget) {
                if (loggedRenderScaleFormatFailure.compareAndSet(false, true)) {
                    Cbbg.LOGGER.warn(
                            "RenderScale detected: failed to allocate float intermediate target; falling back to RGBA8 for RenderScale targets.",
                            e);
                }
                allocateColorStorage(CbbgConfig.PixelFormat.RGBA8);
            } else {
                if (usedFormat == CbbgConfig.PixelFormat.RGBA8) {
                    throw e;
                }
                allocateMainColorStorageWithFallback(requested, usedFormat, e);
            }
            ((RenderTarget) (Object) this).checkStatus();
        }

        ((RenderTarget) (Object) this).clear(clearError);
        ((RenderTarget) (Object) this).unbindRead();

        // Debug/verification hooks: record the actual allocated internal format for special
        // targets we manage (blur intermediates, RenderScale intermediates).
        if (isMenuBlurPostChainTarget) {
            CbbgDebugState.updateBlurInternalFormat(
                    CbbgGlUtil.getTextureInternalFormat2D(this.colorTextureId));
        }
        if (isRenderScaleColorTarget) {
            CbbgDebugState.updateRenderScaleInternalFormat(
                    CbbgGlUtil.getTextureInternalFormat2D(this.colorTextureId));
        }
    }

    private CbbgConfig.PixelFormat allocateMainColorStorageWithFallback(
            CbbgConfig.PixelFormat requested, CbbgConfig.PixelFormat attempt, Throwable cause) {
        if (attempt != null && attempt != CbbgConfig.PixelFormat.RGBA8) {
            if (cause == null && loggedResizeFloatFailure.compareAndSet(false, true)) {
                Cbbg.LOGGER.warn(
                        "Failed to allocate float main render target during resize; falling back.");
            }
        }

        if (attempt == null || attempt == CbbgConfig.PixelFormat.RGBA8) {
            allocateColorStorage(CbbgConfig.PixelFormat.RGBA8);
            return CbbgConfig.PixelFormat.RGBA8;
        }

        if (allocateColorStorage(attempt)) {
            return attempt;
        }

        MainTargetFormatSupport.disable(attempt, cause);

        // Retry once with the next-best effective float format (32F -> 16F), otherwise fall back to
        // vanilla RGBA8.
        CbbgConfig.PixelFormat fallback = MainTargetFormatSupport.getEffective(requested);
        if (fallback != null && fallback != CbbgConfig.PixelFormat.RGBA8
                && allocateColorStorage(fallback)) {
            return fallback;
        }
        if (fallback != null && fallback != CbbgConfig.PixelFormat.RGBA8) {
            MainTargetFormatSupport.disable(fallback, cause);
        }

        allocateColorStorage(CbbgConfig.PixelFormat.RGBA8);
        return CbbgConfig.PixelFormat.RGBA8;
    }

    private CbbgConfig.PixelFormat allocateMenuBlurColorStorageWithFallback(
            CbbgConfig.PixelFormat attempt) {
        if (attempt == null || attempt == CbbgConfig.PixelFormat.RGBA8) {
            allocateColorStorage(CbbgConfig.PixelFormat.RGBA8);
            return CbbgConfig.PixelFormat.RGBA8;
        }

        if (allocateColorStorage(attempt)) {
            return attempt;
        }

        if (loggedMenuBlurFormatFailure.compareAndSet(false, true)) {
            Cbbg.LOGGER.warn(
                    "Menu blur: failed to allocate float intermediate target; falling back to RGBA8 for blur targets.");
        }

        CbbgConfig.PixelFormat fallback = nonMainFallback(attempt);
        if (fallback != CbbgConfig.PixelFormat.RGBA8 && allocateColorStorage(fallback)) {
            return fallback;
        }

        allocateColorStorage(CbbgConfig.PixelFormat.RGBA8);
        return CbbgConfig.PixelFormat.RGBA8;
    }

    private CbbgConfig.PixelFormat allocateRenderScaleColorStorageWithFallback(
            CbbgConfig.PixelFormat attempt) {
        if (attempt == null || attempt == CbbgConfig.PixelFormat.RGBA8) {
            allocateColorStorage(CbbgConfig.PixelFormat.RGBA8);
            return CbbgConfig.PixelFormat.RGBA8;
        }

        if (allocateColorStorage(attempt)) {
            return attempt;
        }

        if (loggedRenderScaleFormatFailure.compareAndSet(false, true)) {
            Cbbg.LOGGER.warn(
                    "RenderScale detected: failed to allocate float intermediate target; falling back to RGBA8 for RenderScale targets.");
        }

        CbbgConfig.PixelFormat fallback = nonMainFallback(attempt);
        if (fallback != CbbgConfig.PixelFormat.RGBA8 && allocateColorStorage(fallback)) {
            return fallback;
        }

        allocateColorStorage(CbbgConfig.PixelFormat.RGBA8);
        return CbbgConfig.PixelFormat.RGBA8;
    }

    private static CbbgConfig.PixelFormat nonMainFallback(CbbgConfig.PixelFormat effective) {
        if (effective == CbbgConfig.PixelFormat.RGBA32F) {
            // Best-effort: try 16F if 32F allocation failed (even if 32F is supported).
            return MainTargetFormatSupport.getEffective(CbbgConfig.PixelFormat.RGBA16F);
        }
        return CbbgConfig.PixelFormat.RGBA8;
    }

    private static boolean isRenderScaleColorTarget(int width, int height) {
        if (!RenderScaleCompat.isLoaded()) {
            return false;
        }

        float scale = RenderScaleCompat.getDitherCoordScale();
        if (!(scale > 0.0F) || scale >= 1.0F) {
            return false;
        }

        Minecraft mc = Minecraft.getInstance();
        int winW = mc.getWindow().getWidth();
        int winH = mc.getWindow().getHeight();
        if (winW <= 0 || winH <= 0) {
            return false;
        }

        int expectedW = Math.max(1, Math.round(winW * scale));
        int expectedH = Math.max(1, Math.round(winH * scale));
        if (Math.abs(width - expectedW) > 1 || Math.abs(height - expectedH) > 1) {
            return false;
        }

        // Sanity: RenderScale intermediate should be smaller than the main target.
        RenderTarget main = mc.getMainRenderTarget();
        return main != null && width < main.width && height < main.height;
    }

    private boolean allocateColorStorage(CbbgConfig.PixelFormat format) {
        int internal = switch (format) {
            case RGBA16F -> GL30.GL_RGBA16F;
            case RGBA32F -> GL30.GL_RGBA32F;
            case RGBA8 -> GL11.GL_RGBA8;
        };
        int type = (format == CbbgConfig.PixelFormat.RGBA8) ? GL11.GL_UNSIGNED_BYTE : GL11.GL_FLOAT;

        GlStateManager._getError();
        GlStateManager._texImage2D(GL11.GL_TEXTURE_2D, 0, internal, this.width, this.height, 0,
                GL11.GL_RGBA, type, null);
        return GlStateManager._getError() == GL11.GL_NO_ERROR;
    }
}
