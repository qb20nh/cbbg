package com.qb20nh.cbbg.render;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.qb20nh.cbbg.CbbgClient;
import com.qb20nh.cbbg.config.CbbgConfig;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/**
 * Tracks whether higher-precision main render target formats are usable on the current OpenGL
 * device/driver.
 *
 * <p>
 * If we detect an allocation/renderability failure, we fall back to a lower format for the
 * remainder of the session to avoid hard-crashes or broken output.
 */
public final class MainTargetFormatSupport {

    private enum SupportState {
        UNKNOWN, SUPPORTED, UNSUPPORTED
    }

    private static volatile SupportState rgba16f = SupportState.UNKNOWN;
    private static volatile SupportState rgba32f = SupportState.UNKNOWN;

    private static final AtomicBoolean loggedDisable16f = new AtomicBoolean(false);
    private static final AtomicBoolean loggedDisable32f = new AtomicBoolean(false);
    private static final AtomicBoolean loggedNoFloatFormats = new AtomicBoolean(false);

    private MainTargetFormatSupport() {}

    public static CbbgConfig.PixelFormat getEffective(CbbgConfig.PixelFormat requested) {
        if (requested == null) {
            return CbbgConfig.PixelFormat.RGBA8;
        }

        return switch (requested) {
            case RGBA8 -> CbbgConfig.PixelFormat.RGBA8;
            case RGBA16F -> isSupported(CbbgConfig.PixelFormat.RGBA16F)
                    ? CbbgConfig.PixelFormat.RGBA16F
                    : CbbgConfig.PixelFormat.RGBA8;
            case RGBA32F -> {
                if (isSupported(CbbgConfig.PixelFormat.RGBA32F)) {
                    yield CbbgConfig.PixelFormat.RGBA32F;
                }
                if (isSupported(CbbgConfig.PixelFormat.RGBA16F)) {
                    yield CbbgConfig.PixelFormat.RGBA16F;
                }
                yield CbbgConfig.PixelFormat.RGBA8;
            }
        };
    }

    public static boolean isSupported(CbbgConfig.PixelFormat format) {
        if (format == null) {
            return true;
        }
        return switch (format) {
            case RGBA8 -> true;
            case RGBA16F -> isRgba16fSupported();
            case RGBA32F -> isRgba32fSupported();
        };
    }

    public static void disable(CbbgConfig.PixelFormat format, @Nullable Throwable cause) {
        if (format == null) {
            return;
        }

        switch (format) {
            case RGBA16F -> disableRgba16f(cause);
            case RGBA32F -> disableRgba32f(cause);
            case RGBA8 -> {
                // never disabled
            }
        }
    }

    /**
     * @return true if we have detected that neither RGBA16F nor RGBA32F can be used as a renderable
     *         main target format on this device/driver.
     */
    public static boolean hasDetectedNoFloatFormats() {
        return rgba16f == SupportState.UNSUPPORTED && rgba32f == SupportState.UNSUPPORTED;
    }

    private static boolean isRgba16fSupported() {
        SupportState state = rgba16f;
        if (state == SupportState.UNKNOWN) {
            rgba16f = probeColorRenderable(GL30.GL_RGBA16F) ? SupportState.SUPPORTED
                    : SupportState.UNSUPPORTED;
            state = rgba16f;
            if (state == SupportState.UNSUPPORTED) {
                disableRgba16f(null);
            }
        }
        return state == SupportState.SUPPORTED;
    }

    private static boolean isRgba32fSupported() {
        SupportState state = rgba32f;
        if (state == SupportState.UNKNOWN) {
            rgba32f = probeColorRenderable(GL30.GL_RGBA32F) ? SupportState.SUPPORTED
                    : SupportState.UNSUPPORTED;
            state = rgba32f;
            if (state == SupportState.UNSUPPORTED) {
                disableRgba32f(null);
            }
        }
        return state == SupportState.SUPPORTED;
    }

    private static void disableRgba16f(@Nullable Throwable cause) {
        rgba16f = SupportState.UNSUPPORTED;
        if (!loggedDisable16f.compareAndSet(false, true)) {
            return;
        }
        if (cause == null) {
            CbbgClient.LOGGER.warn(
                    "RGBA16F main render target disabled (unsupported on this device/driver); falling back.");
        } else {
            CbbgClient.LOGGER.warn(
                    "RGBA16F main render target failed; disabling for this session and falling back.",
                    cause);
        }
        logNoFloatFormatsIfApplicable();
    }

    private static void disableRgba32f(@Nullable Throwable cause) {
        rgba32f = SupportState.UNSUPPORTED;
        if (!loggedDisable32f.compareAndSet(false, true)) {
            return;
        }
        if (cause == null) {
            CbbgClient.LOGGER.warn(
                    "RGBA32F main render target disabled (unsupported on this device/driver); falling back.");
        } else {
            CbbgClient.LOGGER.warn(
                    "RGBA32F main render target failed; disabling for this session and falling back.",
                    cause);
        }
        logNoFloatFormatsIfApplicable();
    }

    private static void logNoFloatFormatsIfApplicable() {
        if (!hasDetectedNoFloatFormats()) {
            return;
        }
        if (!loggedNoFloatFormats.compareAndSet(false, true)) {
            return;
        }
        CbbgClient.LOGGER.warn(
                "No supported float main render target formats detected (RGBA16F/RGBA32F). The main target will remain RGBA8.");
    }

    private static boolean probeColorRenderable(int internalFormat) {
        RenderSystem.assertOnRenderThread();

        int prevTex = GlStateManager._getInteger(GL11.GL_TEXTURE_BINDING_2D);
        int prevFbo = GlStateManager._getInteger(GL30.GL_FRAMEBUFFER_BINDING);

        int tex = 0;
        int fbo = 0;
        try {
            tex = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);

            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, internalFormat, 16, 16, 0, GL11.GL_RGBA,
                    GL11.GL_FLOAT, (ByteBuffer) null);

            fbo = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                    GL11.GL_TEXTURE_2D, tex, 0);

            int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
            return status == GL30.GL_FRAMEBUFFER_COMPLETE;
        } catch (Exception e) {
            CbbgClient.LOGGER.debug("Format probe threw (treating as unsupported).", e);
            return false;
        } finally {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
            if (fbo != 0) {
                GL30.glDeleteFramebuffers(fbo);
            }
            if (tex != 0) {
                GL11.glDeleteTextures(tex);
            }
        }
    }
}


