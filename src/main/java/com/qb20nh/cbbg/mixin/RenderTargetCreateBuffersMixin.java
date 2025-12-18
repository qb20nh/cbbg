package com.qb20nh.cbbg.mixin;

import com.mojang.blaze3d.GpuOutOfMemoryException;
import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.TextureFormat;
import com.qb20nh.cbbg.CbbgClient;
import com.qb20nh.cbbg.compat.renderscale.RenderScaleCompat;
import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.render.GlFormatOverride;
import com.qb20nh.cbbg.render.MainTargetFormatSupport;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.jspecify.annotations.NonNull;
import org.lwjgl.opengl.GL30;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(RenderTarget.class)
public abstract class RenderTargetCreateBuffersMixin {

    private static final AtomicBoolean loggedRenderScaleFormatFailure = new AtomicBoolean(false);

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

        // RenderScale renders the world into its own intermediate TextureTarget labelled
        // "RenderScale", then blits into the true main target. If that intermediate target stays
        // RGBA8, skybox/lighting precision is lost before cbbg's final dither.
        boolean isRenderScaleColor =
                !isMainTarget && RenderScaleCompat.isRenderScaleColorTextureLabel(label);

        if (!isMainTarget && !isRenderScaleColor) {
            return device.createTexture(label, usage, format, width, height, depthOrLayers,
                    mipLevels);
        }

        CbbgConfig.PixelFormat requested = CbbgConfig.get().pixelFormat();
        CbbgConfig.PixelFormat effective = MainTargetFormatSupport.getEffective(requested);
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
            return texture;
        }

        if (isMainTarget) {
            MainTargetFormatSupport.disable(effective, oom != null ? oom : failure);
        } else if (loggedRenderScaleFormatFailure.compareAndSet(false, true)) {
            CbbgClient.LOGGER.warn(
                    "RenderScale detected: failed to allocate float intermediate target; falling back to RGBA8 for RenderScale targets.",
                    oom != null ? oom : failure);
        }

        // Retry once with the next-best effective format (e.g. 32F -> 16F). For the main target we
        // also update the session-wide support state; for RenderScale we just best-effort fallback.
        CbbgConfig.PixelFormat fallback = MainTargetFormatSupport.getEffective(requested);
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

    private static int toGlInternalFormat(CbbgConfig.PixelFormat format) {
        return switch (format) {
            case RGBA16F -> GL30.GL_RGBA16F;
            case RGBA32F -> GL30.GL_RGBA32F;
            case RGBA8 -> throw new IllegalArgumentException("RGBA8 does not require override");
        };
    }
}
