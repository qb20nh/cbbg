package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.NullMarked;
import org.lwjgl.opengl.GL11;

/** Backend readback shared by the world oracle and packaged shader-failure test. */
@NullMarked
final class ReleaseWorldReadback {
  private ReleaseWorldReadback() {}

  static CompletableFuture<byte[]> read(GpuTexture texture, int length, boolean floating) {
    if (texture instanceof GlTexture gl) {
      int bound = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
      int rowLength = GL11.glGetInteger(GL11.GL_PACK_ROW_LENGTH);
      int skipRows = GL11.glGetInteger(GL11.GL_PACK_SKIP_ROWS);
      int skipPixels = GL11.glGetInteger(GL11.GL_PACK_SKIP_PIXELS);
      int alignment = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
      try {
        GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, 0);
        GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, 0);
        GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, 0);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, gl.glId());
        ByteBuffer bytes = ByteBuffer.allocateDirect(length).order(ByteOrder.nativeOrder());
        GL11.glGetTexImage(
            GL11.GL_TEXTURE_2D,
            0,
            GL11.GL_RGBA,
            floating ? GL11.GL_FLOAT : GL11.GL_UNSIGNED_BYTE,
            bytes);
        byte[] result = new byte[length];
        bytes.get(0, result);
        return CompletableFuture.completedFuture(result);
      } finally {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, bound);
        GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, rowLength);
        GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, skipRows);
        GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, skipPixels);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, alignment);
      }
    }
    GpuBuffer buffer =
        RenderSystem.getDevice()
            .createBuffer(
                () -> "CBBG world readback",
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST,
                length);
    CompletableFuture<byte[]> result = new CompletableFuture<>();
    try {
      RenderSystem.getDevice()
          .createCommandEncoder()
          .copyTextureToBuffer(
              texture,
              buffer,
              0,
              () -> {
                try (buffer) {
                  result.complete(ReleaseWorldTarget.mapped(buffer, length));
                } catch (Throwable failure) {
                  result.completeExceptionally(failure);
                }
              },
              0);
    } catch (RuntimeException | Error failure) {
      buffer.close();
      result.completeExceptionally(failure);
    }
    return result;
  }
}
