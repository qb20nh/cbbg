package com.qb20nh.cbbg.gametest;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.NullMarked;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;

/** Verifies native window selection, sRGB preservation and conversion fallback. */
@NullMarked
final class ReleaseWindowState {
  private ReleaseWindowState() {}

  static void check(ClientGameTestContext context) {
    context.waitTicks(5);
    context.runOnClient(
        client -> {
          boolean previous = GL11.glIsEnabled(GL30.GL_FRAMEBUFFER_SRGB);
          int framebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
          try {
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, 0);
            int buffer = GL11.glGetInteger(GL11.GL_DRAW_BUFFER);
            int attachment = buffer == GL11.GL_BACK ? GL11.GL_BACK_LEFT : GL11.GL_FRONT_LEFT;
            JsonObject record = properties(attachment);
            record.addProperty("drawBuffer", buffer);
            record.addProperty("initialSrgbEnabled", previous);
            var loader = FabricLoader.getInstance();
            boolean supported =
                record.get("redBits").getAsInt() == 8
                    && record.get("greenBits").getAsInt() == 8
                    && record.get("blueBits").getAsInt() == 8
                    && record.get("alphaBits").getAsInt() == 8
                    && record.get("componentType").getAsInt() == GL30.GL_UNSIGNED_NORMALIZED
                    && record.get("samples").getAsInt() == 0
                    && !loader.isModLoaded("iris")
                    && !loader.isModLoaded("immediatelyfast")
                    && client.getMainRenderTarget().width == client.getWindow().getWidth()
                    && client.getMainRenderTarget().height == client.getWindow().getHeight();
            boolean srgb = record.get("encoding").getAsInt() == GL30.GL_SRGB;
            for (boolean enabled : new boolean[] {true, false}) {
              closePass();
              if (enabled) GL11.glEnable(GL30.GL_FRAMEBUFFER_SRGB);
              else GL11.glDisable(GL30.GL_FRAMEBUFFER_SRGB);
              client.getMainRenderTarget().blitToScreen();
              boolean intermediate =
                  Objects.requireNonNull(ReleaseShutdownResources.live()).output() != null;
              record.addProperty(
                  enabled ? "srgbEnabledUsedIntermediate" : "srgbDisabledUsedIntermediate",
                  intermediate);
              write(record);
              if (GL11.glIsEnabled(GL30.GL_FRAMEBUFFER_SRGB) != enabled) {
                throw new AssertionError("Window presentation changed sRGB state");
              }
              if (supported && intermediate != (enabled && srgb)) {
                throw new AssertionError(
                    "Window path does not match its format and sRGB state: " + record);
              }
            }
          } finally {
            if (previous) GL11.glEnable(GL30.GL_FRAMEBUFFER_SRGB);
            else GL11.glDisable(GL30.GL_FRAMEBUFFER_SRGB);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, framebuffer);
          }
        });
  }

  private static JsonObject properties(int attachment) {
    JsonObject result = new JsonObject();
    int[] names = {
      GL30.GL_FRAMEBUFFER_ATTACHMENT_RED_SIZE,
      GL30.GL_FRAMEBUFFER_ATTACHMENT_GREEN_SIZE,
      GL30.GL_FRAMEBUFFER_ATTACHMENT_BLUE_SIZE,
      GL30.GL_FRAMEBUFFER_ATTACHMENT_ALPHA_SIZE,
      GL30.GL_FRAMEBUFFER_ATTACHMENT_COMPONENT_TYPE,
      GL30.GL_FRAMEBUFFER_ATTACHMENT_COLOR_ENCODING
    };
    String[] labels = {
      "redBits", "greenBits", "blueBits", "alphaBits", "componentType", "encoding"
    };
    for (int i = 0; i < names.length; i++) {
      result.addProperty(
          labels[i],
          GL30.glGetFramebufferAttachmentParameteri(
              GL30.GL_DRAW_FRAMEBUFFER, attachment, names[i]));
    }
    result.addProperty("samples", GL11.glGetInteger(GL13.GL_SAMPLES));
    return result;
  }

  private static void write(JsonObject record) {
    Path evidence = Path.of(Objects.requireNonNull(System.getProperty("cbbg.test.evidence")));
    try {
      Files.writeString(evidence.resolve("window-state.json"), record + "\n");
    } catch (IOException failure) {
      throw new AssertionError("Cannot save window state", failure);
    }
  }

  private static void closePass() {
    var pass =
        (AutoCloseable)
            Objects.requireNonNull(
                ReleaseShutdownGameTest.field(
                    "com.qb20nh.cbbg.render.DitherPresentation",
                    null,
                    "com.qb20nh.cbbg.render.DitherPass PASS"));
    try {
      pass.close();
    } catch (Exception failure) {
      throw new AssertionError("Cannot reset window test resources", failure);
    }
  }
}
