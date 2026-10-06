package com.qb20nh.cbbg.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.qb20nh.cbbg.config.CbbgConfig;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/** RGBA8 conversion remains available before shaders or noise have loaded. */
public final class Rgba8Readback {
  private Rgba8Readback() {}

  static TextureTarget createTarget(int width, int height) {
    var previous = MenuBlurGuard.getActiveFormat();
    MenuBlurGuard.set(CbbgConfig.PixelFormat.RGBA8);
    try {
      return new TextureTarget(width, height, false, Minecraft.ON_OSX);
    } finally {
      MenuBlurGuard.set(previous);
    }
  }

  public static NativeImage capture(RenderTarget source) {
    RenderSystem.assertOnRenderThread();
    try (@SuppressWarnings("PMD.UnusedLocalVariable")
        DitherPass.GlState state = new DitherPass.GlState()) {
      TextureTarget output = createTarget(source.width, source.height);
      try {
        GlStateManager._disableScissorTest();
        GL11.glDisable(GL30.GL_FRAMEBUFFER_SRGB);
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, source.frameBufferId);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, output.frameBufferId);
        GL30.glBlitFramebuffer(
            0,
            0,
            source.width,
            source.height,
            0,
            0,
            output.width,
            output.height,
            GL11.GL_COLOR_BUFFER_BIT,
            GL11.GL_NEAREST);
        return readScreenshot(output);
      } finally {
        output.destroyBuffers();
      }
    }
  }

  public static NativeImage read(RenderTarget target) {
    return read(target, false);
  }

  public static NativeImage readScreenshot(RenderTarget target) {
    return read(target, true);
  }

  private static NativeImage read(RenderTarget target, boolean opaque) {
    RenderSystem.assertOnRenderThread();
    try (@SuppressWarnings("PMD.UnusedLocalVariable")
        DitherPass.GlState state = new DitherPass.GlState()) {
      NativeImage image = new NativeImage(target.width, target.height, false);
      try {
        GlStateManager._bindTexture(target.getColorTextureId());
        image.downloadTexture(0, opaque);
        image.flipY();
        return image;
      } catch (RuntimeException | Error failure) {
        image.close();
        throw failure;
      }
    }
  }
}
