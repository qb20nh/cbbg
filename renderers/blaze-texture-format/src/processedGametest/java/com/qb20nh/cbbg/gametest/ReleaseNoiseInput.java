package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.TextureFormat;
import java.util.Objects;
import org.jspecify.annotations.NullMarked;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

@NullMarked
final class ReleaseNoiseInput {
  private ReleaseNoiseInput() {}

  @SuppressWarnings("unchecked") // The mapped production field is ThreadLocal<Integer>.
  static GpuTexture create() {
    ThreadLocal<Integer> format =
        (ThreadLocal<Integer>)
            Objects.requireNonNull(
                ReleaseShutdownGameTest.field(
                    "com.qb20nh.cbbg.render.FloatAttachments",
                    null,
                    "java.lang.ThreadLocal FORCED"));
    Integer previous = format.get();
    format.set(GL30.GL_RGBA32F);
    try {
      return RenderSystem.getDevice()
          .createTexture(
              "CBBG noise fixture",
              GpuTexture.USAGE_RENDER_ATTACHMENT
                  | GpuTexture.USAGE_COPY_DST
                  | GpuTexture.USAGE_TEXTURE_BINDING,
              TextureFormat.RGBA8,
              2,
              2,
              1,
              1);
    } finally {
      if (previous == null) format.remove();
      else format.set(previous);
    }
  }

  static void clear(GpuTexture texture, float color) {
    if (!(texture instanceof GlTexture gl)) throw new AssertionError("Expected OpenGL input");
    int previous = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
    boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
    var mask = BufferUtils.createByteBuffer(4);
    GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, mask);
    int framebuffer = GL30.glGenFramebuffers();
    try {
      GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, framebuffer);
      GL30.glFramebufferTexture2D(
          GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, gl.glId(), 0);
      if (GL30.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE)
        throw new AssertionError("Float noise input is not renderable");
      int binding = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
      try {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, gl.glId());
        if (GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT)
            != GL30.GL_RGBA32F)
          throw new AssertionError("Noise input was not allocated as RGBA32F");
      } finally {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, binding);
      }
      GL11.glDisable(GL11.GL_SCISSOR_TEST);
      GL11.glColorMask(true, true, true, true);
      GL30.glClearBufferfv(GL11.GL_COLOR, 0, new float[] {color, color, color, 1});
    } finally {
      GL11.glColorMask(mask.get(0) != 0, mask.get(1) != 0, mask.get(2) != 0, mask.get(3) != 0);
      if (scissor) GL11.glEnable(GL11.GL_SCISSOR_TEST);
      GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previous);
      GL30.glDeleteFramebuffers(framebuffer);
    }
  }
}
