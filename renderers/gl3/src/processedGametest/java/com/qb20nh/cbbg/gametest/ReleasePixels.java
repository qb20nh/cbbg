package com.qb20nh.cbbg.gametest;

import com.google.gson.JsonParser;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.qb20nh.cbbg.reference.DitherReference;
import com.qb20nh.cbbg.render.DitherPass;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.Objects;
import net.caffeinemc.mods.sodium.client.compatibility.workarounds.Workarounds;
import net.fabricmc.fabric.api.client.gametest.v1.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;

/** Checks float input, displayed output and screenshot pixels using the shared CPU oracle. */
@NullMarked
final class ReleasePixels {
  private ReleasePixels() {}

  static void check(ClientGameTestContext context, boolean demo) {
    check(
        context,
        demo,
        FabricLoader.getInstance().isModLoaded("sodium")
            ? Boolean.valueOf(
                !Workarounds.isWorkaroundEnabled(
                    Workarounds.Reference.INTEL_FRAMEBUFFER_BLIT_CRASH_WHEN_UNFOCUSED))
            : "none".equals(System.getProperty("cbbg.test.compat", "none")) ? Boolean.TRUE : null);
  }

  static void check(ClientGameTestContext context, boolean demo, @Nullable Boolean expectedDirect) {
    context.runOnClient(
        client -> {
          client.setScreen(null);
          var main = client.getMainRenderTarget();
          DitherPass pass =
              (DitherPass)
                  Objects.requireNonNull(
                      ReleaseShutdownGameTest.field(
                          "com.qb20nh.cbbg.render.CbbgDither",
                          null,
                          "com.qb20nh.cbbg.render.DitherPass PASS"));
          pass.close();
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
            Object allocated =
                ReleaseShutdownGameTest.field(
                    "com.qb20nh.cbbg.render.DitherPass",
                    pass,
                    "com.mojang.blaze3d.pipeline.TextureTarget output");
            if (expectedDirect != null && (allocated == null) != expectedDirect)
              throw new AssertionError(
                  "Presentation output allocation differs from the expected path");
            ByteBuffer displayed = readScreen(width, height);
            int noise = ReleaseObservations.noise();
            GlStateManager._bindTexture(noise);
            int noiseWidth =
                GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
            int noiseHeight =
                GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
            ByteBuffer noiseBytes = read(noise, noiseWidth * noiseHeight * 4);
            try (var image = Screenshot.takeScreenshot(main)) {
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
              output.blitToScreen(client.getWindow().getWidth(), client.getWindow().getHeight());
              ByteBuffer reference = readScreen(width, height);
              if (!displayed.equals(reference)) {
                throw new AssertionError("Displayed pixels differ from the two-pass reference");
              }
              if (Boolean.TRUE.equals(expectedDirect))
                checkForeignTarget(main, output, width, height);
            }
          } finally {
            GlStateManager._bindTexture(texture);
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
            for (int i = 0; i < packing.length; i++) GL11.glPixelStorei(packing[i], saved[i]);
          }
        });
  }

  private static void checkForeignTarget(
      RenderTarget main, RenderTarget reference, int width, int height) {
    int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
    GlStateManager._activeTexture(GL13.GL_TEXTURE1);
    int binding = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
    GlStateManager._activeTexture(GL13.GL_TEXTURE2);
    int binding2 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
    int read = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
    int draw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
    boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
    boolean srgb = GL11.glIsEnabled(GL30.GL_FRAMEBUFFER_SRGB);
    int[] box = new int[4];
    GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, box);
    TextureTarget target = new TextureTarget(width, height, false, Minecraft.ON_OSX);
    try {
      for (boolean convertSrgb : new boolean[] {false, true}) {
        GlStateManager._disableScissorTest();
        if (convertSrgb) GL11.glEnable(GL30.GL_FRAMEBUFFER_SRGB);
        else GL11.glDisable(GL30.GL_FRAMEBUFFER_SRGB);
        target.setClearColor(0.25f, 0.5f, 0.75f, 0.25f);
        target.clear(Minecraft.ON_OSX);
        target.bindWrite(false);
        GL11.glScissor(3, 5, width - 9, height - 13);
        GlStateManager._enableScissorTest();
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, reference.frameBufferId);
        GlStateManager._activeTexture(GL13.GL_TEXTURE1);
        GlStateManager._bindTexture(reference.getColorTextureId());
        GlStateManager._activeTexture(GL13.GL_TEXTURE2);
        GlStateManager._bindTexture(target.getColorTextureId());
        main.blitToScreen(width, height);
        int[] observedBox = new int[4];
        GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, observedBox);
        if (GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING) != target.frameBufferId
            || GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING)
                != (FabricLoader.getInstance().isModLoaded("sodium") ? 0 : reference.frameBufferId)
            || GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE) != GL13.GL_TEXTURE2
            || GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D) != target.getColorTextureId()
            || !java.util.Arrays.equals(observedBox, new int[] {3, 5, width - 9, height - 13})
            || !GL11.glIsEnabled(GL11.GL_SCISSOR_TEST)
            || GL11.glIsEnabled(GL30.GL_FRAMEBUFFER_SRGB) != convertSrgb) {
          throw new AssertionError("Presentation changed another renderer's OpenGL state");
        }
        GlStateManager._activeTexture(GL13.GL_TEXTURE1);
        if (GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D) != reference.getColorTextureId())
          throw new AssertionError("Presentation changed another renderer's texture binding");
        ByteBuffer direct = read(target.getColorTextureId(), width * height * 4);
        try (var screenshot = Screenshot.takeScreenshot(main)) {
          if (screenshot.getWidth() != width)
            throw new AssertionError("Invalid reference screenshot");
        }
        GlStateManager._disableScissorTest();
        target.clear(Minecraft.ON_OSX);
        target.bindWrite(false);
        GlStateManager._enableScissorTest();
        reference.blitToScreen(width, height);
        if (!direct.equals(read(target.getColorTextureId(), width * height * 4)))
          throw new AssertionError("Foreign-target pixels differ from the two-pass reference");
      }
    } finally {
      target.destroyBuffers();
      GlStateManager._activeTexture(GL13.GL_TEXTURE1);
      GlStateManager._bindTexture(binding);
      GlStateManager._activeTexture(GL13.GL_TEXTURE2);
      GlStateManager._bindTexture(binding2);
      GlStateManager._activeTexture(active);
      GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
      GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
      GL11.glScissor(box[0], box[1], box[2], box[3]);
      if (scissor) GlStateManager._enableScissorTest();
      else GlStateManager._disableScissorTest();
      if (srgb) GL11.glEnable(GL30.GL_FRAMEBUFFER_SRGB);
      else GL11.glDisable(GL30.GL_FRAMEBUFFER_SRGB);
    }
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

  private static ByteBuffer readScreen(int width, int height) {
    int previous = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
    GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, 0);
    int buffer = GL11.glGetInteger(GL11.GL_READ_BUFFER);
    ByteBuffer pixels = BufferUtils.createByteBuffer(width * height * 4);
    try {
      GL11.glReadBuffer(GL11.GL_BACK);
      GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
      return pixels;
    } finally {
      GL11.glReadBuffer(buffer);
      GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previous);
    }
  }
}
