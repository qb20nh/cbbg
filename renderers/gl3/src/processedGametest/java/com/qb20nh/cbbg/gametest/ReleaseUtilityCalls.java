package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.qb20nh.cbbg.api.DitherOptions;
import com.qb20nh.cbbg.render.DitherPass;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import org.jspecify.annotations.NullMarked;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;

@NullMarked
final class ReleaseUtilityCalls {
  private ReleaseUtilityCalls() {}

  static TextureTarget render(
      DitherPass pass, TextureTarget input, UtilitiesBackend.Noise noise, DitherOptions options) {
    int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
    int vao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
    int arrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
    GlStateManager._activeTexture(GL13.GL_TEXTURE2);
    int texture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
    int customVao = GL30.glGenVertexArrays();
    try {
      GlStateManager._bindTexture(noise.texture());
      BufferUploader.invalidate();
      GlStateManager._glBindVertexArray(customVao);
      TextureTarget result = pass.render(input, noise.texture(), options);
      if (GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE) != GL13.GL_TEXTURE2
          || GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D) != noise.texture()
          || GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING) != customVao
          || GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING) != arrayBuffer) {
        throw new AssertionError(
            "Utility pass leaked active texture, texture binding, VAO or buffer state");
      }
      return result;
    } finally {
      BufferUploader.invalidate();
      GlStateManager._glBindVertexArray(vao);
      GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, arrayBuffer);
      GL30.glDeleteVertexArrays(customVao);
      GlStateManager._activeTexture(GL13.GL_TEXTURE2);
      GlStateManager._bindTexture(texture);
      GlStateManager._activeTexture(active);
    }
  }

  static void resize(TextureTarget target, int width, int height) {
    target.resize(width, height, Minecraft.ON_OSX);
  }

  static void checkClosed(
      DitherPass pass, TextureTarget output, TextureTarget input, UtilitiesBackend.Noise noise) {
    int owned = output.getColorTextureId();
    pass.close();
    if (GL11.glIsTexture(owned)) throw new AssertionError("Utility pass retained its output");
    if (!GL11.glIsTexture(noise.texture()) || !GL11.glIsTexture(input.getColorTextureId())) {
      throw new AssertionError("Utility pass closed externally owned input textures");
    }
  }

  static void capture(TextureTarget target, Consumer<NativeImage> callback) {
    callback.accept(Screenshot.takeScreenshot(target));
  }
}
