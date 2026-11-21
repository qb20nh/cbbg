package com.qb20nh.cbbg.render;

import com.mojang.blaze3d.systems.RenderSystem;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;

/** Window formats that preserve the RGBA8 intermediate's conversion. */
@NullMarked
final class WindowFramebuffer {
  private static @Nullable Object device;
  private static boolean rgba8;
  private static boolean srgb;

  private WindowFramebuffer() {}

  @SuppressWarnings("ReferenceEquality") // Each device owns a distinct window framebuffer.
  static boolean supportsDirectDrawing() {
    Object current = RenderSystem.getDevice();
    // skipcq: JAVA-E1053
    if (device != current) {
      inspect();
      device = current;
    }
    // skipcq: JAVA-W1060
    return rgba8 && (!srgb || !GL11.glIsEnabled(GL30.GL_FRAMEBUFFER_SRGB));
  }

  private static void inspect() {
    int previous = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
    try {
      GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, 0);
      int attachment =
          switch (GL11.glGetInteger(GL11.GL_DRAW_BUFFER)) {
            case GL11.GL_BACK, GL11.GL_BACK_LEFT -> GL11.GL_BACK_LEFT;
            case GL11.GL_FRONT, GL11.GL_FRONT_LEFT -> GL11.GL_FRONT_LEFT;
            default -> 0;
          };
      if (attachment == 0) {
        rgba8 = false;
        srgb = false;
        return;
      }
      int alpha = property(attachment, GL30.GL_FRAMEBUFFER_ATTACHMENT_ALPHA_SIZE);
      rgba8 =
          property(attachment, GL30.GL_FRAMEBUFFER_ATTACHMENT_RED_SIZE) == 8
              && property(attachment, GL30.GL_FRAMEBUFFER_ATTACHMENT_GREEN_SIZE) == 8
              && property(attachment, GL30.GL_FRAMEBUFFER_ATTACHMENT_BLUE_SIZE) == 8
              && (alpha == 0 || alpha == 8)
              && property(attachment, GL30.GL_FRAMEBUFFER_ATTACHMENT_COMPONENT_TYPE)
                  == GL30.GL_UNSIGNED_NORMALIZED
              && GL11.glGetInteger(GL13.GL_SAMPLES) == 0;
      srgb = property(attachment, GL30.GL_FRAMEBUFFER_ATTACHMENT_COLOR_ENCODING) == GL30.GL_SRGB;
    } finally {
      GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previous);
    }
  }

  private static int property(int attachment, int name) {
    return GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, attachment, name);
  }
}
