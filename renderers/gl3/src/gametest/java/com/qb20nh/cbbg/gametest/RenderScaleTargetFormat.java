package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import org.jspecify.annotations.NullMarked;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

@NullMarked
final class RenderScaleTargetFormat {
  private RenderScaleTargetFormat() {}

  static String name(RenderTarget target) {
    int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
    try {
      GlStateManager._bindTexture(target.getColorTextureId());
      int format =
          GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT);
      return switch (format) {
        case GL30.GL_RGBA32F -> "RGBA32F";
        case GL30.GL_RGBA16F -> "RGBA16F";
        case GL11.GL_RGBA8 -> "RGBA8";
        default -> throw new AssertionError("Unexpected GL target format: " + format);
      };
    } finally {
      GlStateManager._bindTexture(previous);
    }
  }
}
