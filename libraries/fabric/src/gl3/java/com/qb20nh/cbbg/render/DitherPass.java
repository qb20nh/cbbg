package com.qb20nh.cbbg.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.qb20nh.cbbg.api.DitherOptions;
import java.nio.ByteBuffer;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/** Independent GPU pass. Input and noise textures remain owned by the caller. */
@NullMarked
public final class DitherPass implements AutoCloseable {
  private @Nullable TextureTarget output;

  /** Output remains valid until resize or close. All calls must run on the render thread. */
  public TextureTarget render(RenderTarget input, int noiseTexture, DitherOptions options) {
    return render(input.getColorTextureId(), input.width, input.height, noiseTexture, options);
  }

  public TextureTarget render(
      int inputTexture, int width, int height, int noiseTexture, DitherOptions options) {
    RenderSystem.assertOnRenderThread();
    if (inputTexture <= 0 || noiseTexture <= 0 || width <= 0 || height <= 0) {
      throw new IllegalArgumentException("Input and noise textures and dimensions must be valid");
    }
    var shader = CbbgShaders.get(options.demo());
    if (shader == null) throw new IllegalStateException("CBBG shaders have not loaded");
    try (@SuppressWarnings("PMD.UnusedLocalVariable")
        GlState state = new GlState()) {
      if (output == null || output.width != width || output.height != height) {
        close();
        output = createTarget(width, height);
      }
      shader.setSampler("InSampler", inputTexture);
      shader.setSampler("NoiseSampler", noiseTexture);
      shader.safeGetUniform("Strength").set(options.strength());
      shader.safeGetUniform("CoordScale").set(options.scaleX(), options.scaleY());
      output.bindWrite(true);
      GlStateManager._disableDepthTest();
      GlStateManager._depthMask(false);
      GlStateManager._disableBlend();
      GlStateManager._disableScissorTest();
      GlStateManager._colorMask(true, true, true, true);
      GL11.glDisable(GL30.GL_FRAMEBUFFER_SRGB);
      try {
        shader.apply();
        var vertices =
            RenderSystem.renderThreadTesselator()
                .begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLIT_SCREEN);
        vertices.addVertex(0, 0, 0);
        vertices.addVertex(1, 0, 0);
        vertices.addVertex(1, 1, 0);
        vertices.addVertex(0, 1, 0);
        BufferUploader.draw(vertices.buildOrThrow());
      } finally {
        shader.clear();
      }
      return output;
    }
  }

  private static TextureTarget createTarget(int width, int height) {
    var target = new TextureTarget(width, height, false, Minecraft.ON_OSX);
    GlStateManager._bindTexture(target.getColorTextureId());
    // The embedding application's framebuffer mixins may change vanilla's allocation format.
    GL11.glTexImage2D(
        GL11.GL_TEXTURE_2D,
        0,
        GL11.GL_RGBA8,
        width,
        height,
        0,
        GL11.GL_RGBA,
        GL11.GL_UNSIGNED_BYTE,
        (ByteBuffer) null);
    target.bindWrite(false);
    target.checkStatus();
    return target;
  }

  @Override
  public void close() {
    RenderSystem.assertOnRenderThread();
    if (output != null) {
      output.destroyBuffers();
      output = null;
    }
  }

  /** Keeps utility and screenshot passes from leaking framebuffer or raster state. */
  static final class GlState implements AutoCloseable {
    private final int read = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
    private final int draw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
    private final int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
    private final int vertexArray = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
    private final int arrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
    private final int[] viewport = new int[4];
    private final boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
    private final boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
    private final boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
    private final boolean srgb = GL11.glIsEnabled(GL30.GL_FRAMEBUFFER_SRGB);
    private final boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
    private final byte[] colorMask = new byte[4];
    private final int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
    private final int[] textures = new int[2];

    GlState() {
      GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
      var mask = org.lwjgl.BufferUtils.createByteBuffer(4);
      GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, mask);
      mask.get(colorMask);
      for (int i = 0; i < textures.length; i++) {
        GlStateManager._activeTexture(GL13.GL_TEXTURE0 + i);
        textures[i] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
      }
      GlStateManager._activeTexture(GL13.GL_TEXTURE0);
    }

    @Override
    public void close() {
      BufferUploader.invalidate();
      GlStateManager._glBindVertexArray(vertexArray);
      GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, arrayBuffer);
      GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
      GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
      GlStateManager._glUseProgram(program);
      RenderSystem.viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
      if (depth) GlStateManager._enableDepthTest();
      else GlStateManager._disableDepthTest();
      if (blend) GlStateManager._enableBlend();
      else GlStateManager._disableBlend();
      if (scissor) GlStateManager._enableScissorTest();
      else GlStateManager._disableScissorTest();
      if (srgb) GL11.glEnable(GL30.GL_FRAMEBUFFER_SRGB);
      else GL11.glDisable(GL30.GL_FRAMEBUFFER_SRGB);
      GlStateManager._depthMask(depthMask);
      GlStateManager._colorMask(
          colorMask[0] != 0, colorMask[1] != 0, colorMask[2] != 0, colorMask[3] != 0);
      for (int i = 0; i < textures.length; i++) {
        GlStateManager._activeTexture(GL13.GL_TEXTURE0 + i);
        GlStateManager._bindTexture(textures[i]);
      }
      GlStateManager._activeTexture(active);
    }
  }
}
