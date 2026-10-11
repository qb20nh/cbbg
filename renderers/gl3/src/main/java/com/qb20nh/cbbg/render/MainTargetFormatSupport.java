package com.qb20nh.cbbg.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.qb20nh.cbbg.Cbbg;
import com.qb20nh.cbbg.config.CbbgConfig.PixelFormat;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/** Session capability policy plus full-size allocation fallback, preserving depth attachments. */
public final class MainTargetFormatSupport {
  private static final Set<PixelFormat> tested = new HashSet<>(Set.of(PixelFormat.RGBA8));
  private static final Set<PixelFormat> unsupported = new HashSet<>();

  private MainTargetFormatSupport() {}

  public static PixelFormat getEffective(@Nullable PixelFormat requested) {
    return FormatPolicy.effective(requested, MainTargetFormatSupport::isSupported);
  }

  public static boolean isSupported(PixelFormat format) {
    if (tested.add(format) && !probe(format)) disable(format, null);
    return !unsupported.contains(format);
  }

  public static void disable(PixelFormat format, @Nullable Throwable failure) {
    if (format != PixelFormat.RGBA8 && unsupported.add(format)) {
      tested.add(format);
      Cbbg.LOGGER.warn(
          "{} render target unavailable; falling back for this session", format, failure);
    }
  }

  public static boolean hasDetectedNoFloatFormats() {
    return unsupported.contains(PixelFormat.RGBA16F) && unsupported.contains(PixelFormat.RGBA32F);
  }

  public static int internalFormat(PixelFormat format) {
    return switch (format) {
      case RGBA16F -> GL30.GL_RGBA16F;
      case RGBA32F -> GL30.GL_RGBA32F;
      case RGBA8 -> GL11.GL_RGBA8;
    };
  }

  /** Reuses vanilla's color texture and framebuffer, including their clear color and depth. */
  public static void allocate(RenderTarget target, PixelFormat requested, boolean mainTarget) {
    RenderSystem.assertOnRenderThreadOrInit();
    int texture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
    int read = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
    int draw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
    try {
      GlStateManager._bindTexture(target.getColorTextureId());
      GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, target.frameBufferId);
      PixelFormat attempt = getEffective(requested);
      while (true) {
        GlStateManager._getError();
        GlStateManager._texImage2D(
            GL11.GL_TEXTURE_2D,
            0,
            internalFormat(attempt),
            target.width,
            target.height,
            0,
            GL11.GL_RGBA,
            attempt == PixelFormat.RGBA8 ? GL11.GL_UNSIGNED_BYTE : GL11.GL_FLOAT,
            null);
        boolean allocated = GlStateManager._getError() == GL11.GL_NO_ERROR;
        if (allocated
            && GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) == GL30.GL_FRAMEBUFFER_COMPLETE) {
          return;
        }
        if (attempt == PixelFormat.RGBA8) {
          throw new IllegalStateException("Could not allocate RGBA8 render target");
        }
        if (mainTarget) disable(attempt, null);
        // Auxiliary failures are local: do not disable a working main format.
        attempt =
            attempt == PixelFormat.RGBA32F ? getEffective(PixelFormat.RGBA16F) : PixelFormat.RGBA8;
      }
    } finally {
      GlStateManager._bindTexture(texture);
      GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
      GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
    }
  }

  private static boolean probe(PixelFormat format) {
    RenderSystem.assertOnRenderThreadOrInit();
    int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
    int read = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
    int draw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
    int texture = GL11.glGenTextures();
    int framebuffer = GL30.glGenFramebuffers();
    try {
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
      GL11.glTexImage2D(
          GL11.GL_TEXTURE_2D,
          0,
          internalFormat(format),
          16,
          16,
          0,
          GL11.GL_RGBA,
          GL11.GL_FLOAT,
          (ByteBuffer) null);
      GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
      GL30.glFramebufferTexture2D(
          GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, texture, 0);
      return GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) == GL30.GL_FRAMEBUFFER_COMPLETE;
    } catch (RuntimeException failure) {
      Cbbg.LOGGER.debug("Float render-target capability probe failed", failure);
      return false;
    } finally {
      GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
      GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
      GL30.glDeleteFramebuffers(framebuffer);
      GL11.glDeleteTextures(texture);
    }
  }
}
