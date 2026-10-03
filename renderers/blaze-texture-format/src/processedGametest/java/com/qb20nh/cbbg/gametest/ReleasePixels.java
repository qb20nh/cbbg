package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.textures.GpuTexture;
import com.qb20nh.cbbg.reference.DitherReference;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

/** Compares the packaged presentation shader with the shared CPU reference. */
@NullMarked
final class ReleasePixels {
  private ReleasePixels() {}

  static void check(ClientGameTestContext context, boolean demo) {
    check(context, demo, 1f);
  }

  static void check(ClientGameTestContext context, boolean demo, float coordScale) {
    var capture = context.computeOnClient(client -> draw(client, demo, coordScale));
    context.waitFor(client -> capture.isDone(), 200);
    capture.join();
  }

  private static CompletableFuture<@Nullable Void> draw(
      Minecraft client, boolean demo, float coordScale) {
    client.setScreen(null);
    var main = client.getMainRenderTarget();
    int width = main.width;
    int height = main.height;
    GpuTexture input = Objects.requireNonNull(main.getColorTexture());
    FloatBuffer source = BufferUtils.createFloatBuffer(width * height * 4);
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        source.put(0.23f + 0.45f * x / width).put(0.13f + 0.67f * y / height).put(0.4f);
        // Keep fractional alpha away from halfway UNORM conversion ties.
        source.put(
            switch (y % 4) {
              case 0 -> 0f;
              case 1 -> 0.25f;
              case 2 -> 0.75f;
              default -> 1f;
            });
      }
    }
    source.flip();
    int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
    int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
    int[] packing = {
      GL11.GL_PACK_ROW_LENGTH, GL11.GL_PACK_SKIP_PIXELS, GL11.GL_PACK_SKIP_ROWS,
      GL11.GL_UNPACK_ROW_LENGTH, GL11.GL_UNPACK_SKIP_PIXELS, GL11.GL_UNPACK_SKIP_ROWS
    };
    int[] saved = new int[packing.length];
    for (int i = 0; i < packing.length; i++) {
      saved[i] = GL11.glGetInteger(packing[i]);
      GL11.glPixelStorei(packing[i], 0);
    }
    try {
      GlStateManager._bindTexture(((GlTexture) input).glId());
      GL11.glTexSubImage2D(
          GL11.GL_TEXTURE_2D, 0, 0, 0, width, height, GL11.GL_RGBA, GL11.GL_FLOAT, source);
      // Read back stored float values so half-float conversion is part of the input.
      GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_FLOAT, source);
      GlStateManager._bindTexture(previous);
      long before = ReleaseObservations.presentations();
      main.blitToScreen();
      if (ReleaseObservations.presentations() != before + 1) {
        throw new AssertionError("The synthetic image bypassed CBBG presentation");
      }
      var noise = ReleaseObservations.noise();
      int noiseWidth = noise.getWidth(0);
      int noiseHeight = noise.getHeight(0);
      ByteBuffer noiseBytes = read(noise.texture(), noiseWidth * noiseHeight * 4);
      ByteBuffer actual =
          read(Objects.requireNonNull(ReleaseObservations.output()).texture(), width * height * 4);
      for (int y = 0; y < height; y++) {
        for (int x = 0; x < width; x++) {
          int offset = (y * width + x) * 4;
          int tile =
              (DitherReference.noiseCoordinate(y, coordScale, noiseHeight) * noiseWidth
                      + DitherReference.noiseCoordinate(x, coordScale, noiseWidth))
                  * 4;
          for (int channel = 0; channel < 3; channel++) {
            int expected =
                DitherReference.channel(
                    source.get(offset + channel),
                    Byte.toUnsignedInt(noiseBytes.get(tile + channel)),
                    2,
                    x,
                    width,
                    demo);
            int value = Byte.toUnsignedInt(actual.get(offset + channel));
            if (value != expected) {
              throw new AssertionError(
                  "Dither pixel "
                      + x
                      + ","
                      + y
                      + " channel "
                      + channel
                      + ": expected "
                      + expected
                      + ", got "
                      + value
                      + ", demo="
                      + demo
                      + ", source="
                      + source.get(offset + channel)
                      + ", noise="
                      + Byte.toUnsignedInt(noiseBytes.get(tile + channel)));
            }
          }
          int alpha = Math.round(source.get(offset + 3) * 255);
          if (Byte.toUnsignedInt(actual.get(offset + 3)) != alpha) {
            throw new AssertionError(
                "Dithering changed alpha at "
                    + x
                    + ","
                    + y
                    + ": source="
                    + source.get(offset + 3)
                    + ", expected="
                    + alpha
                    + ", got="
                    + Byte.toUnsignedInt(actual.get(offset + 3)));
          }
        }
      }
      CompletableFuture<@Nullable Void> capture = new CompletableFuture<>();
      Screenshot.takeScreenshot(
          main,
          1,
          image -> {
            try (image) {
              if (image.getWidth() != width || image.getHeight() != height) {
                throw new AssertionError("Screenshot dimensions differ from the displayed image");
              }
              for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                  int offset = (y * width + x) * 4;
                  int expected =
                      0xff000000
                          | Byte.toUnsignedInt(actual.get(offset)) << 16
                          | Byte.toUnsignedInt(actual.get(offset + 1)) << 8
                          | Byte.toUnsignedInt(actual.get(offset + 2));
                  if (image.getPixel(x, height - y - 1) != expected) {
                    throw new AssertionError(
                        "Screenshot differs from presentation at " + x + "," + y);
                  }
                }
              }
              var evidence =
                  java.nio.file.Path.of(
                      Objects.requireNonNull(System.getProperty("cbbg.test.evidence")));
              String name = demo ? "pixels-demo" : "pixels-enabled";
              image.writeToFile(
                  evidence.resolve(name + (coordScale == 1f ? "" : "-scaled") + ".png"));
              capture.complete(null);
            } catch (Throwable failure) {
              capture.completeExceptionally(failure);
            }
          });
      return capture;
    } finally {
      GlStateManager._activeTexture(active);
      GlStateManager._bindTexture(previous);
      for (int i = 0; i < packing.length; i++) GL11.glPixelStorei(packing[i], saved[i]);
    }
  }

  private static ByteBuffer read(GpuTexture texture, int bytes) {
    ByteBuffer pixels = BufferUtils.createByteBuffer(bytes);
    int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
    try {
      GlStateManager._bindTexture(((GlTexture) texture).glId());
      GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
    } finally {
      GlStateManager._bindTexture(previous);
    }
    return pixels;
  }
}
