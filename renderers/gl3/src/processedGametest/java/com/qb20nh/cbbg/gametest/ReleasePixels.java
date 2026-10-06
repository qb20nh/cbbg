package com.qb20nh.cbbg.gametest;

import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.GlStateManager;
import com.qb20nh.cbbg.reference.DitherReference;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.Objects;
import net.fabricmc.fabric.api.client.gametest.v1.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Screenshot;
import org.jspecify.annotations.NullMarked;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/** Checks float input, displayed output and screenshot pixels using the shared CPU oracle. */
@NullMarked
final class ReleasePixels {
  private ReleasePixels() {}

  static void check(ClientGameTestContext context, boolean demo) {
    context.runOnClient(
        client -> {
          client.setScreen(null);
          var main = client.getMainRenderTarget();
          int width = main.width;
          int height = main.height;
          double strength;
          try (var reader =
              Files.newBufferedReader(
                  FabricLoader.getInstance().getConfigDir().resolve("cbbg.json"))) {
            strength =
                JsonParser.parseReader(reader).getAsJsonObject().get("strength").getAsDouble();
          } catch (IOException failure) {
            throw new AssertionError("Cannot read configured dither strength", failure);
          }
          var source = BufferUtils.createFloatBuffer(width * height * 4);
          for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
              source
                  .put(0.23f + 0.45f * x / width)
                  .put(0.13f + 0.67f * y / height)
                  .put(0.4f)
                  .put(
                      switch (y % 4) {
                        case 0 -> 0f;
                        case 1 -> 0.25f;
                        case 2 -> 0.75f;
                        default -> 1f;
                      });
            }
          }
          source.flip();
          int texture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
          int read = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
          int draw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
          int[] packing = {
            GL11.GL_PACK_ROW_LENGTH,
            GL11.GL_PACK_SKIP_PIXELS,
            GL11.GL_PACK_SKIP_ROWS,
            GL11.GL_UNPACK_ROW_LENGTH,
            GL11.GL_UNPACK_SKIP_PIXELS,
            GL11.GL_UNPACK_SKIP_ROWS
          };
          int[] saved = new int[packing.length];
          for (int i = 0; i < packing.length; i++) {
            saved[i] = GL11.glGetInteger(packing[i]);
            GL11.glPixelStorei(packing[i], 0);
          }
          try {
            GlStateManager._bindTexture(main.getColorTextureId());
            GL11.glTexSubImage2D(
                GL11.GL_TEXTURE_2D, 0, 0, 0, width, height, GL11.GL_RGBA, GL11.GL_FLOAT, source);
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_FLOAT, source);
            GlStateManager._bindTexture(texture);
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            long before = ReleaseObservations.presentations();
            main.blitToScreen(client.getWindow().getWidth(), client.getWindow().getHeight());
            if (ReleaseObservations.presentations() != before + 1) {
              throw new AssertionError("Synthetic float input bypassed CBBG presentation");
            }
            int noise = ReleaseObservations.noise();
            GlStateManager._bindTexture(noise);
            int noiseWidth =
                GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
            int noiseHeight =
                GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
            ByteBuffer noiseBytes = read(noise, noiseWidth * noiseHeight * 4);
            var output = Objects.requireNonNull(ReleaseObservations.output());
            ByteBuffer actual = read(output.getColorTextureId(), width * height * 4);
            for (int y = 0; y < height; y++) {
              for (int x = 0; x < width; x++) {
                int offset = (y * width + x) * 4;
                int tile =
                    (DitherReference.noiseCoordinate(y, 1, noiseHeight) * noiseWidth
                            + DitherReference.noiseCoordinate(x, 1, noiseWidth))
                        * 4;
                for (int channel = 0; channel < 3; channel++) {
                  int expected =
                      DitherReference.channel(
                          source.get(offset + channel),
                          Byte.toUnsignedInt(noiseBytes.get(tile + channel)),
                          strength,
                          x,
                          width,
                          demo);
                  if (Byte.toUnsignedInt(actual.get(offset + channel)) != expected) {
                    throw new AssertionError(
                        "Dither pixel mismatch at " + x + "," + y + " channel " + channel);
                  }
                }
                if (Byte.toUnsignedInt(actual.get(offset + 3))
                    != Math.round(source.get(offset + 3) * 255)) {
                  throw new AssertionError("Dithering changed input alpha at " + x + "," + y);
                }
              }
            }
            try (var image = Screenshot.takeScreenshot(main)) {
              for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                  int offset = (y * width + x) * 4;
                  int expected =
                      0xff000000
                          | Byte.toUnsignedInt(actual.get(offset)) << 16
                          | Byte.toUnsignedInt(actual.get(offset + 1)) << 8
                          | Byte.toUnsignedInt(actual.get(offset + 2));
                  if (ReleaseImagePixels.argb(image, x, height - 1 - y) != expected) {
                    throw new AssertionError(
                        "Screenshot differs from presentation at " + x + "," + y);
                  }
                }
              }
            }
          } finally {
            GlStateManager._bindTexture(texture);
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
            for (int i = 0; i < packing.length; i++) GL11.glPixelStorei(packing[i], saved[i]);
          }
        });
  }

  private static ByteBuffer read(int texture, int size) {
    int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
    ByteBuffer pixels = BufferUtils.createByteBuffer(size);
    try {
      GlStateManager._bindTexture(texture);
      GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
      return pixels;
    } finally {
      GlStateManager._bindTexture(previous);
    }
  }
}
