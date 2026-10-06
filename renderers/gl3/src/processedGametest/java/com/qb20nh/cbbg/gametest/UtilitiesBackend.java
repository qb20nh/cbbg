package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import com.qb20nh.cbbg.api.NoiseVolume;
import net.fabricmc.fabric.api.client.gametest.v1.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

@NullMarked
final class UtilitiesBackend {
  private UtilitiesBackend() {}

  static void checkBackend(ClientGameTestContext context) {
    ReleaseGraphics.check(context);
  }

  static @Nullable Screen screen(Minecraft client) {
    return client.screen;
  }

  static TextureTarget input(int width, int height) {
    var target = new TextureTarget(width, height, false, Minecraft.ON_OSX);
    clear(target);
    return target;
  }

  static void clear(TextureTarget target) {
    int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
    int[] packing = {
      GL11.GL_UNPACK_ROW_LENGTH,
      GL11.GL_UNPACK_SKIP_PIXELS,
      GL11.GL_UNPACK_SKIP_ROWS,
      GL11.GL_UNPACK_ALIGNMENT,
      GL11.GL_PACK_ROW_LENGTH,
      GL11.GL_PACK_SKIP_PIXELS,
      GL11.GL_PACK_SKIP_ROWS,
      GL11.GL_PACK_ALIGNMENT
    };
    int[] saved = new int[packing.length];
    for (int i = 0; i < packing.length; i++) {
      saved[i] = GL11.glGetInteger(packing[i]);
      GL11.glPixelStorei(packing[i], i % 4 == 3 ? 4 : 0);
    }
    var pixels = BufferUtils.createByteBuffer(target.width * target.height * 4);
    for (int i = 0; i < target.width * target.height; i++) {
      pixels.put((byte) 127).put((byte) 127).put((byte) 127).put((byte) 255);
    }
    pixels.flip();
    try {
      GlStateManager._bindTexture(target.getColorTextureId());
      GL11.glTexSubImage2D(
          GL11.GL_TEXTURE_2D,
          0,
          0,
          0,
          target.width,
          target.height,
          GL11.GL_RGBA,
          GL11.GL_UNSIGNED_BYTE,
          pixels);
      ReleaseUtilityCalls.capture(
          target,
          image -> {
            try (image) {
              for (int y = 0; y < target.height; y++) {
                for (int x = 0; x < target.width; x++) {
                  int pixel = ReleaseImagePixels.argb(image, x, y);
                  if (pixel != 0xff7f7f7f) {
                    throw new AssertionError(
                        "Utility source fixture upload mismatch at "
                            + x
                            + ","
                            + y
                            + ": "
                            + Integer.toHexString(pixel));
                  }
                }
              }
            }
          });
    } finally {
      for (int i = 0; i < packing.length; i++) GL11.glPixelStorei(packing[i], saved[i]);
      GlStateManager._bindTexture(previous);
    }
  }

  static Noise noise(NoiseVolume volume, int frame) {
    int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
    int texture = TextureUtil.generateTextureId();
    try {
      TextureUtil.prepareImage(texture, volume.width(), volume.height());
      GlStateManager._bindTexture(texture);
      try (NativeImage image = new NativeImage(volume.width(), volume.height(), false)) {
        for (int y = 0; y < volume.height(); y++) {
          for (int x = 0; x < volume.width(); x++) {
            image.setPixelRGBA(x, y, volume.pixelABGR(x, y, frame));
          }
        }
        image.upload(0, 0, 0, false);
      }
      GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
      GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
      return new Noise(texture);
    } catch (RuntimeException | Error failure) {
      TextureUtil.releaseTextureId(texture);
      throw failure;
    } finally {
      GlStateManager._bindTexture(previous);
    }
  }

  record Noise(int texture) implements AutoCloseable {
    @Override
    public void close() {
      TextureUtil.releaseTextureId(texture);
    }
  }
}
