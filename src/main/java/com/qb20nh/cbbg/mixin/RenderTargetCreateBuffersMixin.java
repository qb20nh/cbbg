package com.qb20nh.cbbg.mixin;

import com.mojang.blaze3d.GpuOutOfMemoryException;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.TextureFormat;
import com.qb20nh.cbbg.Cbbg;
import com.qb20nh.cbbg.CbbgClient;
import com.qb20nh.cbbg.compat.renderscale.RenderScaleCompat;
import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.debug.CbbgGlNames;
import com.qb20nh.cbbg.render.GlFormatOverride;
import com.qb20nh.cbbg.render.MainTargetFormatSupport;
import com.qb20nh.cbbg.render.MenuBlurGuard;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.jspecify.annotations.NonNull;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(RenderTarget.class)
public abstract class RenderTargetCreateBuffersMixin {

    private static final AtomicBoolean loggedRenderScaleFormatFailure = new AtomicBoolean(false);
    private static final AtomicBoolean loggedMenuBlurFormatFailure = new AtomicBoolean(false);
    private static final AtomicBoolean loggedMenuBlurAllocInfo = new AtomicBoolean(false);

    @Redirect(method = "createBuffers", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/GpuDevice;createTexture(Ljava/util/function/Supplier;ILcom/mojang/blaze3d/textures/TextureFormat;IIII)Lcom/mojang/blaze3d/textures/GpuTexture;"))
    private GpuTexture cbbg$createBuffers$createTexture(GpuDevice device, Supplier<String> label,
            int usage, @NonNull TextureFormat format, int width, int height, int depthOrLayers,
            int mipLevels) {
        // cbbg only upgrades RGBA8 color targets, and only while active.
        if (format != TextureFormat.RGBA8 || !CbbgClient.isEnabled()) {
            return device.createTexture(label, usage, format, width, height, depthOrLayers,
                    mipLevels);
        }

        // MainTarget can be resized via the base RenderTarget.resize() path, which calls
        // createBuffers(). Ensure the main color attachment stays float even after resizes.
        boolean isMainTarget = ((Object) this instanceof MainTarget);

        // --- ImmediatelyFast compat: do not remove ---
        // Rationale: ImmediatelyFast (and many other mods) define their own custom RenderTargets
        // with explicit labels (e.g. "ImmediatelyFast Sign Atlas FBO"). Vanilla post-processing
        // chains allocate internal targets as TextureTarget(null, ...), which get labels like
        // "FBO N". We only upgrade those vanilla internal targets while the menu blur chain is
        // executing, to avoid accidentally changing mod-owned RenderTargets.
        boolean isMenuBlurPostChainInternal =
                !isMainTarget && MenuBlurGuard.isActive() && label.get().startsWith("FBO ");

        // RenderScale renders the world into its own intermediate TextureTarget labelled
        // "RenderScale", then blits into the true main target. If that intermediate target stays
        // RGBA8, skybox/lighting precision is lost before cbbg's final dither.
        boolean isRenderScaleColor =
                !isMainTarget && RenderScaleCompat.isRenderScaleColorTextureLabel(label);

        if (!isMainTarget && !isRenderScaleColor && !isMenuBlurPostChainInternal) {
            return device.createTexture(label, usage, format, width, height, depthOrLayers,
                    mipLevels);
        }

        CbbgConfig.PixelFormat requested = CbbgConfig.get().pixelFormat();
        CbbgConfig.PixelFormat effective =
                isMenuBlurPostChainInternal ? MenuBlurGuard.getActiveFormat()
                        : MainTargetFormatSupport.getEffective(requested);
        if (effective == null) {
            effective = MainTargetFormatSupport.getEffective(requested);
        }
        if (effective == CbbgConfig.PixelFormat.RGBA8) {
            return device.createTexture(label, usage, format, width, height, depthOrLayers,
                    mipLevels);
        }

        GpuTexture texture = null;
        GpuOutOfMemoryException oom = null;
        Exception failure = null;

        if (isMainTarget) {
            GlFormatOverride.pushMainTargetColor();
        } else {
            GlFormatOverride.pushFormat(toGlInternalFormat(effective));
        }
        try {
            texture = device.createTexture(label, usage, format, width, height, depthOrLayers,
                    mipLevels);
        } catch (GpuOutOfMemoryException e) {
            oom = e;
        } catch (Exception e) {
            failure = e;
        } finally {
            if (isMainTarget) {
                GlFormatOverride.popMainTargetColor();
            } else {
                GlFormatOverride.popFormat();
            }
        }

        if (oom == null && failure == null) {
            if (isMenuBlurPostChainInternal && loggedMenuBlurAllocInfo.compareAndSet(false, true)) {
                // Diagnostic only: confirm the blur post-chain internal target is actually float.
                // This helps distinguish "blur re-quantizes to RGBA8" from "dither strength needs
                // adjustment for blurred gradients".
                int internal = getTextureInternalFormat(texture);
                Cbbg.LOGGER.info(
                        "cbbg menu blur alloc: label=\"{}\" requested={} effective={} glInternal={}",
                        label.get(), requested.getSerializedName(), effective.getSerializedName(),
                        CbbgGlNames.glInternalName(internal));
            }
            return texture;
        }

        if (isMainTarget) {
            MainTargetFormatSupport.disable(effective, oom != null ? oom : failure);
        } else if (isRenderScaleColor) {
            if (loggedRenderScaleFormatFailure.compareAndSet(false, true)) {
                Cbbg.LOGGER.warn(
                        "RenderScale detected: failed to allocate float intermediate target; falling back to RGBA8 for RenderScale targets.",
                        oom != null ? oom : failure);
            }
        } else if (isMenuBlurPostChainInternal) {
            if (loggedMenuBlurFormatFailure.compareAndSet(false, true)) {
                Cbbg.LOGGER.warn(
                        "Menu blur: failed to allocate float intermediate target; falling back to RGBA8 for blur targets.",
                        oom != null ? oom : failure);
            }
        }

        // Retry once with the next-best effective format (e.g. 32F -> 16F). For the main target we
        // also update the session-wide support state; for RenderScale / menu blur we best-effort
        // downgrade without changing global support state.
        CbbgConfig.PixelFormat fallback =
                isMainTarget ? MainTargetFormatSupport.getEffective(requested)
                        : nonMainFallback(effective);
        if (fallback == CbbgConfig.PixelFormat.RGBA8) {
            return device.createTexture(label, usage, format, width, height, depthOrLayers,
                    mipLevels);
        }

        if (isMainTarget) {
            GlFormatOverride.pushMainTargetColor();
        } else {
            GlFormatOverride.pushFormat(toGlInternalFormat(fallback));
        }
        try {
            return device.createTexture(label, usage, format, width, height, depthOrLayers,
                    mipLevels);
        } catch (GpuOutOfMemoryException e) {
            if (isMainTarget) {
                MainTargetFormatSupport.disable(fallback, e);
                throw e;
            }
            return device.createTexture(label, usage, format, width, height, depthOrLayers,
                    mipLevels);
        } catch (Exception e) {
            if (isMainTarget) {
                MainTargetFormatSupport.disable(fallback, e);
            }
            return device.createTexture(label, usage, format, width, height, depthOrLayers,
                    mipLevels);
        } finally {
            if (isMainTarget) {
                GlFormatOverride.popMainTargetColor();
            } else {
                GlFormatOverride.popFormat();
            }
        }
    }

    private static CbbgConfig.PixelFormat nonMainFallback(CbbgConfig.PixelFormat effective) {
        if (effective == CbbgConfig.PixelFormat.RGBA32F) {
            // Best-effort: try 16F if 32F allocation failed (even if 32F is supported).
            return MainTargetFormatSupport.getEffective(CbbgConfig.PixelFormat.RGBA16F);
        }
        return CbbgConfig.PixelFormat.RGBA8;
    }

    private static int toGlInternalFormat(CbbgConfig.PixelFormat format) {
        return switch (format) {
            case RGBA16F -> GL30.GL_RGBA16F;
            case RGBA32F -> GL30.GL_RGBA32F;
            case RGBA8 -> throw new IllegalArgumentException("RGBA8 does not require override");
        };
    }

    private static int getTextureInternalFormat(GpuTexture texture) {
        int prev = GlStateManager._getInteger(GL11.GL_TEXTURE_BINDING_2D);
        try {
            int id = ((GlTexture) texture).glId();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
            return GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0,
                    GL11.GL_TEXTURE_INTERNAL_FORMAT);
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prev);
        }
    }
}
