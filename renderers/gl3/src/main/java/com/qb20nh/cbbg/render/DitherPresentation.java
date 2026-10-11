package com.qb20nh.cbbg.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;

/** OpenGL operations for the shared noise and presentation lifecycle. */
final class DitherPresentation {
  private static int texture;

  private DitherPresentation() {}

  static boolean hasOverlay() {
    return Minecraft.getInstance().getOverlay() != null;
  }

  static void allocate(int size) {
    int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
    try {
      texture = TextureUtil.generateTextureId();
      TextureUtil.prepareImage(texture, size, size);
      GlStateManager._bindTexture(texture);
      GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
      GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
    } finally {
      GlStateManager._bindTexture(previous);
    }
  }

  static boolean isReady() {
    return texture > 0;
  }

  static int texture() {
    return texture;
  }

  static void upload(NativeImage image) {
    int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
    try {
      GlStateManager._bindTexture(texture);
      // NativeImage owns the CPU pixels; upload must not close a controller-owned frame.
      image.upload(0, 0, 0, 0, 0, image.getWidth(), image.getHeight(), false, false, false, false);
    } finally {
      GlStateManager._bindTexture(previous);
    }
  }

  static boolean hasDetectedNoFloatFormats() {
    return MainTargetFormatSupport.hasDetectedNoFloatFormats();
  }

  static void close() {
    CbbgDither.closeGpu();
    if (texture > 0) {
      TextureUtil.releaseTextureId(texture);
      texture = 0;
    }
  }
}
