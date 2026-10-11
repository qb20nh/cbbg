package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.platform.GlStateManager;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.Supplier;
import org.jspecify.annotations.NullMarked;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL21;

/**
 * Tightly packed GL texture readback that restores every pack parameter and pack-buffer binding.
 */
@NullMarked
final class ReleaseWorldReadback {
  private ReleaseWorldReadback() {}

  static byte[] read(int texture, int length, boolean floating) {
    int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
    try {
      GlStateManager._bindTexture(texture);
      return packed(
          () -> {
            ByteBuffer bytes = ByteBuffer.allocateDirect(length).order(ByteOrder.nativeOrder());
            GL11.glGetTexImage(
                GL11.GL_TEXTURE_2D,
                0,
                GL11.GL_RGBA,
                floating ? GL11.GL_FLOAT : GL11.GL_UNSIGNED_BYTE,
                bytes);
            byte[] result = new byte[length];
            bytes.get(0, result);
            return result;
          });
    } finally {
      GlStateManager._bindTexture(previous);
    }
  }

  static <T> T packed(Supplier<T> action) {
    int[] parameters = {
      GL11.GL_PACK_ROW_LENGTH,
      GL11.GL_PACK_SKIP_ROWS,
      GL11.GL_PACK_SKIP_PIXELS,
      GL11.GL_PACK_ALIGNMENT,
      GL11.GL_PACK_SWAP_BYTES,
      GL11.GL_PACK_LSB_FIRST,
      GL12.GL_PACK_IMAGE_HEIGHT,
      GL12.GL_PACK_SKIP_IMAGES
    };
    int[] original = new int[parameters.length];
    for (int i = 0; i < parameters.length; i++) original[i] = GL11.glGetInteger(parameters[i]);
    int buffer = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
    try {
      GlStateManager._glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
      for (int parameter : parameters)
        GL11.glPixelStorei(parameter, parameter == GL11.GL_PACK_ALIGNMENT ? 1 : 0);
      return action.get();
    } finally {
      for (int i = 0; i < parameters.length; i++) GL11.glPixelStorei(parameters[i], original[i]);
      GlStateManager._glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, buffer);
    }
  }
}
