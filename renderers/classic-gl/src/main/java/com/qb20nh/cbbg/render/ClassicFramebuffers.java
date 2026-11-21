package com.qb20nh.cbbg.render;

import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.qb20nh.cbbg.Cbbg;
import com.qb20nh.cbbg.CbbgClient;
import com.qb20nh.cbbg.config.CbbgConfig;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.IntConsumer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/** Classic OpenGL attachment allocation; policy and settings remain in the shared core. */
public final class ClassicFramebuffers {
  private static final EnumMap<CbbgConfig.PixelFormat, Boolean> SUPPORTED =
      new EnumMap<>(CbbgConfig.PixelFormat.class);
  private static final Set<RenderTarget> TARGETS = Collections.newSetFromMap(new WeakHashMap<>());
  private static CbbgConfig.PixelFormat previousFormat;
  private static boolean previousActive;

  private ClassicFramebuffers() {}

  public static void track(RenderTarget target) {
    TARGETS.add(target);
  }

  public static boolean owns(RenderTarget target) {
    return target instanceof MainTarget || TARGETS.contains(target);
  }

  public static boolean hasDetectedNoFloatFormats() {
    return Boolean.FALSE.equals(SUPPORTED.get(CbbgConfig.PixelFormat.RGBA16F))
        && Boolean.FALSE.equals(SUPPORTED.get(CbbgConfig.PixelFormat.RGBA32F));
  }

  public static void tick() {
    CbbgConfig settings = CbbgConfig.get();
    boolean active = CbbgClient.isEnabled();
    if (previousFormat == settings.pixelFormat() && previousActive == active) return;
    previousFormat = settings.pixelFormat();
    previousActive = active;
    for (RenderTarget target : TARGETS.toArray(new RenderTarget[0])) {
      if (target.frameBufferId >= 0) target.resize(target.width, target.height, false);
    }
  }

  public static void allocateColor(int originalFormat, IntConsumer allocate) {
    RenderSystem.assertOnRenderThreadOrInit();
    if (!CbbgClient.isEnabled()) {
      allocate.accept(originalFormat);
      return;
    }
    while (true) {
      CbbgConfig.PixelFormat format =
          FormatPolicy.effective(
              CbbgConfig.get().pixelFormat(),
              candidate ->
                  candidate == CbbgConfig.PixelFormat.RGBA8
                      || SUPPORTED.computeIfAbsent(candidate, value -> probe(glFormat(value))));
      if (format == CbbgConfig.PixelFormat.RGBA8) {
        allocate.accept(originalFormat);
        return;
      }
      allocate.accept(glFormat(format));
      int error = GL11.glGetError();
      int storedFormat =
          GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT);
      // Other GL hooks can consume the error flag; verify the resulting storage too.
      if (error == GL11.GL_NO_ERROR && storedFormat == glFormat(format)) return;
      SUPPORTED.put(format, false);
      Cbbg.LOGGER.warn(
          "Classic framebuffer allocation failed for {} (GL {}, stored format {}); falling back",
          format,
          error,
          storedFormat);
    }
  }

  private static int glFormat(CbbgConfig.PixelFormat format) {
    return format == CbbgConfig.PixelFormat.RGBA32F ? GL30.GL_RGBA32F : GL30.GL_RGBA16F;
  }

  private static boolean probe(int format) {
    int textureBinding = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
    int readBinding = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
    int drawBinding = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
    int texture = GL11.glGenTextures();
    int framebuffer = GL30.glGenFramebuffers();
    try {
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
      GL11.glTexImage2D(
          GL11.GL_TEXTURE_2D, 0, format, 4, 4, 0, GL11.GL_RGBA, GL11.GL_FLOAT, (ByteBuffer) null);
      GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
      GL30.glFramebufferTexture2D(
          GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, texture, 0);
      int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
      int error = GL11.glGetError();
      return status == GL30.GL_FRAMEBUFFER_COMPLETE
          && error == GL11.GL_NO_ERROR
          && GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT)
              == format;
    } finally {
      GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readBinding);
      GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawBinding);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureBinding);
      GL30.glDeleteFramebuffers(framebuffer);
      GL11.glDeleteTextures(texture);
    }
  }
}
