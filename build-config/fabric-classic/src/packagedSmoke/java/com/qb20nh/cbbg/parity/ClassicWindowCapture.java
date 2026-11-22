package com.qb20nh.cbbg.parity;

import com.mojang.blaze3d.platform.NativeImage;
import com.qb20nh.cbbg.gametest.IrisFixture;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

/** Reads the presented image immediately before the window swaps buffers. */
public final class ClassicWindowCapture {
  private record Capture(Path path, int width, int height, boolean iris) {}

  private static Capture pending;

  private ClassicWindowCapture() {}

  static void request(Minecraft client, String filename) {
    request(client, filename, true);
  }

  static void requestPixels(Minecraft client, String filename) {
    request(client, filename, false);
  }

  private static void request(Minecraft client, String filename, boolean iris) {
    var window = client.getWindow();
    pending =
        new Capture(
            client.gameDirectory.toPath().resolve(filename),
            window.getWidth(),
            window.getHeight(),
            iris);
  }

  static boolean isPending() {
    return pending != null;
  }

  public static void beforeSwap() {
    Capture capture = pending;
    if (capture == null) return;
    pending = null;
    ByteBuffer pixels = MemoryUtil.memAlloc(capture.width() * capture.height() * 4);
    try (var image = new NativeImage(capture.width(), capture.height(), false)) {
      readBackBuffer(capture, pixels);
      for (int y = capture.height() - 1; y >= 0; y--) {
        for (int x = 0; x < capture.width(); x++) {
          int red = pixels.get() & 255;
          int green = pixels.get() & 255;
          int blue = pixels.get() & 255;
          int alpha = pixels.get() & 255;
          image.setPixelRGBA(x, y, (alpha << 24) | (blue << 16) | (green << 8) | red);
        }
      }
      image.writeToFile(capture.path());
      if (capture.iris()) {
        IrisFixture.assertFinalShader(
            image, "The presented frame did not contain the Iris shader fixture");
      } else {
        ClassicOpenGlPixels.checkDraws();
      }
    } catch (IOException failure) {
      throw new IllegalStateException("Could not record OpenGL presentation", failure);
    } finally {
      MemoryUtil.memFree(pixels);
    }
  }

  private static void readBackBuffer(Capture capture, ByteBuffer pixels) {
    int framebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
    GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, 0);
    int readBuffer = GL11.glGetInteger(GL11.GL_READ_BUFFER);
    try {
      GL11.glReadBuffer(GL11.GL_BACK);
      GL11.glReadPixels(
          0, 0, capture.width(), capture.height(), GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
    } finally {
      GL11.glReadBuffer(readBuffer);
      GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, framebuffer);
    }
  }
}
