package com.qb20nh.cbbg.parity;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.qb20nh.cbbg.reference.DitherReference;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/** Compares the normal window blit and screenshot to a deterministic CPU fixture. */
public final class ClassicOpenGlPixels {
  private static final String[] MODES = {"demo", "enabled", "disabled"};
  private static final int STRIPS = 32;
  private static int phase;
  private static int readyAt = -1;
  private static boolean requested;
  private static boolean drawn;
  private static long initialDraws;

  private ClassicOpenGlPixels() {}

  static boolean tick(Minecraft client, int tick) throws Exception {
    if (readyAt < 0) readyAt = tick + 8;
    if (tick < readyAt) return false;
    if (!requested) {
      requested = true;
      return false;
    }
    if (!drawn || ClassicWindowCapture.isPending()) return false;
    String mode = MODES[phase];
    boolean enabled = !mode.equals("disabled");
    compare(client.gameDirectory.toPath(), mode, enabled);
    if (++phase == MODES.length) return true;
    ClassicPackagedSmoke.command(client, "cbbg mode set " + MODES[phase]);
    readyAt = tick + 8;
    requested = false;
    drawn = false;
    return false;
  }

  public static void beforeBlit(Minecraft client) {
    if (!requested || drawn) return;
    RenderTarget target = client.getMainRenderTarget();
    String mode = MODES[phase];
    ClassicPackagedSmoke.requireFormat(
        target, mode.equals("disabled") ? GL11.GL_RGBA8 : GL30.GL_RGBA16F);
    fill(target);
    initialDraws = ClassicPackagedSmoke.presentationCount();
    Path directory = client.gameDirectory.toPath();
    try (NativeImage capture = Screenshot.takeScreenshot(target)) {
      capture.writeToFile(directory.resolve("opengl-shot-" + mode + ".png"));
    } catch (IOException failure) {
      throw new IllegalStateException("Could not save the OpenGL screenshot", failure);
    }
    ClassicWindowCapture.requestPixels(client, "opengl-window-" + mode + ".png");
    drawn = true;
  }

  static void checkDraws() {
    long draws = ClassicPackagedSmoke.presentationCount() - initialDraws;
    int expected = MODES[phase].equals("disabled") ? 0 : 2;
    if (draws != expected) {
      throw new AssertionError(
          "Unexpected OpenGL screenshot and presentation dither draws: " + draws);
    }
    System.out.println("CBBG_CLASSIC_OPENGL_PIXEL_DRAWS mode=" + MODES[phase] + " draws=" + draws);
  }

  private static void fill(RenderTarget target) {
    int previous = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
    boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
    int[] box = new int[4];
    GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, box);
    GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, target.frameBufferId);
    GlStateManager._enableScissorTest();
    try {
      int middle = target.height / 2;
      GlStateManager._scissorBox(0, 0, target.width, target.height - middle);
      clear(0, false);
      for (int strip = 0; strip < STRIPS; strip++) {
        int start = (strip * target.width + STRIPS - 1) / STRIPS;
        int end = ((strip + 1) * target.width + STRIPS - 1) / STRIPS;
        GlStateManager._scissorBox(start, target.height - middle, end - start, middle);
        clear(strip, true);
      }
    } finally {
      GlStateManager._scissorBox(box[0], box[1], box[2], box[3]);
      if (!scissor) GlStateManager._disableScissorTest();
      GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previous);
    }
  }

  private static void clear(int strip, boolean ramp) {
    GL30.glClearBufferfv(
        GL11.GL_COLOR,
        0,
        new float[] {source(strip, ramp, 0), source(strip, ramp, 1), source(strip, ramp, 2), 0.5f});
  }

  private static float source(int strip, boolean ramp, int channel) {
    return (ramp ? 17 + (strip * (31 + channel * 7) + channel * 131) % 992 : 89 + channel * 288)
        / 1024f;
  }

  private static void compare(Path directory, String mode, boolean enabled) throws IOException {
    try (var screenshot = read(directory.resolve("opengl-shot-" + mode + ".png"));
        var presented = read(directory.resolve("opengl-window-" + mode + ".png"));
        var noise = read(directory.resolve(".cbbg/stbn_16x16x8_0.png"))) {
      int width = 803;
      int height = 601;
      if (screenshot.getWidth() != width
          || screenshot.getHeight() != height
          || presented.getWidth() != width
          || presented.getHeight() != height
          || noise.getWidth() != 16
          || noise.getHeight() != 16) {
        throw new AssertionError("Unexpected OpenGL fixture dimensions");
      }
      boolean demo = mode.equals("demo");
      int screenshotErrors = 0;
      int windowErrors = 0;
      int topOriginErrors = 0;
      int alphaErrors = 0;
      int changed = 0;
      for (int y = 0; y < height; y++) {
        int bottom = DitherReference.noiseCoordinate(height - 1 - y, 1, 16);
        int top = DitherReference.noiseCoordinate(y, 1, 16);
        for (int x = 0; x < width; x++) {
          int shot = screenshot.getPixelRGBA(x, y);
          int window = presented.getPixelRGBA(x, y);
          if ((shot >>> 24) != 255) alphaErrors++;
          int nx = DitherReference.noiseCoordinate(x, 1, 16);
          int sample = noise.getPixelRGBA(nx, bottom);
          int topSample = noise.getPixelRGBA(nx, top);
          for (int channel = 0; channel < 3; channel++) {
            int shift = 8 * channel;
            float input = source(x * STRIPS / width, y < height / 2, channel);
            int expected =
                DitherReference.shaderChannel(
                    input, (sample >>> shift) & 255, enabled ? 2 : 0, x, width, demo);
            int topExpected =
                DitherReference.shaderChannel(
                    input, (topSample >>> shift) & 255, enabled ? 2 : 0, x, width, demo);
            int pixel = (window >>> shift) & 255;
            if (((shot >>> shift) & 255) != expected) screenshotErrors++;
            if (pixel != expected) windowErrors++;
            if (pixel != topExpected) topOriginErrors++;
            if (pixel != DitherReference.shaderChannel(input, 0, 0, x, width, false)) changed++;
          }
        }
      }
      System.out.println(
          "CBBG_CLASSIC_OPENGL_PIXELS mode="
              + mode
              + " channels="
              + width * height * 3
              + " screenshotMismatches="
              + screenshotErrors
              + " windowMismatches="
              + windowErrors
              + " topOriginMismatches="
              + topOriginErrors
              + " alphaMismatches="
              + alphaErrors
              + " changedChannels="
              + changed);
      if (screenshotErrors != 0
          || windowErrors != 0
          || alphaErrors != 0
          || enabled != (changed > 0)
          || enabled != (topOriginErrors > 0)) {
        throw new AssertionError("OpenGL output differs from the deterministic CPU pixels");
      }
    }
  }

  private static NativeImage read(Path file) throws IOException {
    try (var stream = Files.newInputStream(file)) {
      return NativeImage.read(stream);
    }
  }
}
