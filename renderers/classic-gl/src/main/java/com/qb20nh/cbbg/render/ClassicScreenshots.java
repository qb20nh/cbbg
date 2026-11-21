package com.qb20nh.cbbg.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import net.minecraft.client.Screenshot;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;

public final class ClassicScreenshots {
  private ClassicScreenshots() {}

  public static NativeImage capture(RenderTarget source) {
    RenderSystem.assertOnRenderThread();
    int read = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
    int draw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
    int activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
    int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
    boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
    boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
    boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
    boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
    Runnable restoreProjection = ProjectionState.capture();
    int[] viewport = new int[4];
    float[] clearColor = new float[4];
    GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
    GL11.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, clearColor);
    GlStateManager._activeTexture(GL13.GL_TEXTURE0);
    int texture0 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
    GlStateManager._activeTexture(GL13.GL_TEXTURE1);
    int texture1 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
    GlStateManager._activeTexture(GL13.GL_TEXTURE0);
    try (MemoryStack stack = MemoryStack.stackPush()) {
      ByteBuffer colorMask = stack.malloc(4);
      GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, colorMask);
      TextureTarget output = null;
      try {
        GlStateManager._disableScissorTest();
        output = new TextureTarget(source.width, source.height, false, false);
        output.bindWrite(true);
        ClassicDither.blitForScreenshot(source);
        // This ordinary RGBA8 target bypasses the main-target capture mixin.
        return Screenshot.takeScreenshot(output);
      } finally {
        if (output != null) output.destroyBuffers();
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
        GlStateManager._viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        GlStateManager._clearColor(clearColor[0], clearColor[1], clearColor[2], clearColor[3]);
        GlStateManager._colorMask(
            colorMask.get(0) != 0,
            colorMask.get(1) != 0,
            colorMask.get(2) != 0,
            colorMask.get(3) != 0);
        GlStateManager._depthMask(depthMask);
        if (depth) GlStateManager._enableDepthTest();
        else GlStateManager._disableDepthTest();
        if (blend) GlStateManager._enableBlend();
        else GlStateManager._disableBlend();
        if (scissor) GlStateManager._enableScissorTest();
        GlStateManager._activeTexture(GL13.GL_TEXTURE0);
        GlStateManager._bindTexture(texture0);
        GlStateManager._activeTexture(GL13.GL_TEXTURE1);
        GlStateManager._bindTexture(texture1);
        GlStateManager._activeTexture(activeTexture);
        GlStateManager._glUseProgram(program);
        restoreProjection.run();
      }
    }
  }
}
