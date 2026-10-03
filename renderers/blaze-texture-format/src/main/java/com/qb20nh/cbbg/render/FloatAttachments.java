package com.qb20nh.cbbg.render;

import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.textures.GpuTexture;
import com.qb20nh.cbbg.config.CbbgConfig.PixelFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

@NullMarked
public final class FloatAttachments {
  private static final ThreadLocal<Integer> FORCED = new ThreadLocal<>();

  private FloatAttachments() {}

  public static @Nullable Integer forcedInternalFormat() {
    return FORCED.get();
  }

  @SuppressWarnings("ReferenceEquality") // Avoid adding an exception to itself.
  public static GpuTexture create(PixelFormat requested, Supplier<GpuTexture> allocation) {
    @Nullable GpuTexture[] allocated = new GpuTexture[1];
    List<RuntimeException> failures = new ArrayList<>();
    FormatPolicy.effective(
        requested,
        candidate -> {
          Integer previous = FORCED.get();
          FORCED.set(candidate == PixelFormat.RGBA32F ? GL30.GL_RGBA32F : GL30.GL_RGBA16F);
          try {
            GpuTexture texture = allocation.get();
            boolean accepted = false;
            try {
              if (!renderable(texture)) {
                throw new IllegalStateException("Float color attachment is not renderable");
              }
              allocated[0] = texture;
              accepted = true;
              return true;
            } finally {
              if (!accepted) texture.close();
            }
          } catch (RuntimeException failure) {
            failures.add(failure);
            return false;
          } finally {
            if (previous == null) FORCED.remove();
            else FORCED.set(previous);
          }
        });
    if (allocated[0] != null) return allocated[0];
    Integer previous = FORCED.get();
    FORCED.set(GL11.GL_RGBA8);
    try {
      return allocation.get();
    } catch (RuntimeException failure) {
      for (RuntimeException earlier : failures) {
        if (earlier != failure) failure.addSuppressed(earlier);
      }
      throw failure;
    } finally {
      if (previous == null) FORCED.remove();
      else FORCED.set(previous);
    }
  }

  private static boolean renderable(GpuTexture texture) {
    if (!(texture instanceof GlTexture gl)) return false;
    int read = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
    int draw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
    int framebuffer = GL30.glGenFramebuffers();
    try {
      GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
      GL30.glFramebufferTexture2D(
          GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, gl.glId(), 0);
      return GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) == GL30.GL_FRAMEBUFFER_COMPLETE;
    } finally {
      GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
      GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
      GL30.glDeleteFramebuffers(framebuffer);
    }
  }

  public static int internalFormat(GpuTexture texture) {
    if (!(texture instanceof GlTexture gl)) return GL11.GL_RGBA8;
    int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
    try {
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, gl.glId());
      return GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT);
    } finally {
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous);
    }
  }
}
