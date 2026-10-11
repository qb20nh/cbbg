package com.qb20nh.cbbg.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.qb20nh.cbbg.config.CbbgConfig;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
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
        ReadbackState state = new ReadbackState()) {
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
        ReadbackState state = new ReadbackState()) {
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

  private static final class ReadbackState implements AutoCloseable {
    private final int read = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
    private final int draw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
    private final int[] viewport = new int[4];
    private final boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
    private final boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
    private final boolean srgb = GL11.glIsEnabled(GL30.GL_FRAMEBUFFER_SRGB);
    private final int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
    private final int[] textures = new int[2];

    ReadbackState() {
      GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
      for (int i = 0; i < textures.length; i++) {
        GlStateManager._activeTexture(GL13.GL_TEXTURE0 + i);
        textures[i] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
      }
      GlStateManager._activeTexture(GL13.GL_TEXTURE0);
    }

    @Override
    public void close() {
      GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
      GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
      RenderSystem.viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
      if (depth) GlStateManager._enableDepthTest();
      else GlStateManager._disableDepthTest();
      if (scissor) GlStateManager._enableScissorTest();
      else GlStateManager._disableScissorTest();
      if (srgb) GL11.glEnable(GL30.GL_FRAMEBUFFER_SRGB);
      else GL11.glDisable(GL30.GL_FRAMEBUFFER_SRGB);
      for (int i = 0; i < textures.length; i++) {
        GlStateManager._activeTexture(GL13.GL_TEXTURE0 + i);
        GlStateManager._bindTexture(textures[i]);
      }
      GlStateManager._activeTexture(active);
    }
  }
}
