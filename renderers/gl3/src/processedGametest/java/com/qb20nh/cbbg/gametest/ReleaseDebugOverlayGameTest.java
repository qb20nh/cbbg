package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.platform.GlStateManager;
import com.qb20nh.cbbg.gametest.mixin.ReleaseDebugOverlayAccess;
import com.qb20nh.cbbg.gametest.mixin.ReleaseLightTextureAccess;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import net.fabricmc.fabric.api.client.gametest.v1.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.NullMarked;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/** Compares the real legacy F3 lines with live GL attachments and controller state. */
@NullMarked
public final class ReleaseDebugOverlayGameTest implements FabricClientGameTest {
  @Override
  public void runTest(ClientGameTestContext context) {
    ReleaseGraphics.check(context);
    String original =
        context.computeOnClient(
            client -> ReleaseWorldPixelsGameTest.currentSettings().get("mode").getAsString());
    boolean visible = context.computeOnClient(client -> client.getDebugOverlay().showDebugScreen());
    try (var world = context.worldBuilder().create()) {
      ReleaseViewport.waitForChunks(world);
      context.runOnClient(
          client -> {
            if (!client.getDebugOverlay().showDebugScreen())
              client.getDebugOverlay().toggleOverlay();
          });
      for (String mode : List.of("enabled", "disabled", "demo")) {
        command(context, mode);
        context.waitFor(client -> mode.equals("disabled") ? frames() == 0 : frames() > 0, 600);
        context.waitTicks(5);
        context.runOnClient(client -> check(client, mode));
      }
    } finally {
      command(context, original.toLowerCase(Locale.ROOT));
      context.runOnClient(
          client -> {
            if (client.getDebugOverlay().showDebugScreen() != visible)
              client.getDebugOverlay().toggleOverlay();
          });
    }
  }

  private static void command(ClientGameTestContext context, String mode) {
    context.runOnClient(
        client ->
            ReleaseGenerationGameTest.command(
                Objects.requireNonNull(ReleaseCommands.getActiveDispatcher()),
                ReleaseGenerationGameTest.silentSource(),
                "mode set " + mode));
  }

  private static void check(Minecraft client, String mode) {
    if (!client.getDebugOverlay().showDebugScreen())
      throw new AssertionError("Legacy F3 overlay is hidden");
    List<String> lines =
        ((ReleaseDebugOverlayAccess) client.getDebugOverlay())
            .cbbg$information().stream().filter(line -> line.startsWith("cbbg:")).toList();
    String upper = mode.toUpperCase(Locale.ROOT);
    String first = "cbbg: mode=" + upper + " (user=" + upper + ") iris=0 dis=0";
    int light =
        ((ReleaseLightTextureAccess) client.gameRenderer.lightTexture()).cbbg$texture().getId();
    String second =
        "cbbg: main="
            + format(client.getMainRenderTarget().getColorTextureId())
            + " lm="
            + format(light)
            + " srgb="
            + (GL11.glIsEnabled(GL30.GL_FRAMEBUFFER_SRGB) ? 1 : 0)
            + " stbn="
            + frame()
            + "/"
            + frames();
    if (!lines.equals(List.of(first, second)))
      throw new AssertionError(
          "Wrong actual F3 state: " + lines + "; expected " + first + ", " + second);
    try {
      Path path =
          Path.of(
              Objects.requireNonNull(System.getProperty("cbbg.test.evidence")),
              "debug-" + mode + ".txt");
      Files.createDirectories(Objects.requireNonNull(path.getParent()));
      Files.writeString(path, String.join("\n", lines) + "\n");
    } catch (java.io.IOException failure) {
      throw new AssertionError("Cannot retain actual F3 state", failure);
    }
  }

  private static String format(int texture) {
    if (texture <= 0) throw new AssertionError("Live F3 texture is missing");
    int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
    try {
      GlStateManager._bindTexture(texture);
      int format =
          GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT);
      if (format <= 0
          || GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH) <= 0
          || GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT) <= 0)
        throw new AssertionError("Live F3 texture has no allocated storage");
      return switch (format) {
        case GL11.GL_RGBA8 -> "RGBA8";
        case GL30.GL_RGBA16F -> "RGBA16F";
        case GL30.GL_RGBA32F -> "RGBA32F";
        default -> "0x" + Integer.toHexString(format);
      };
    } finally {
      GlStateManager._bindTexture(previous);
    }
  }

  private static int frames() {
    return state("int getStbnFrames()");
  }

  private static int frame() {
    return state("int getCurrentStbnFrameIndex()");
  }

  private static int state(String member) {
    String owner = "com.qb20nh.cbbg.render.DitherController";
    try {
      return (Integer)
          Objects.requireNonNull(
              Class.forName(ReleaseMapping.className(owner))
                  .getMethod(ReleaseMapping.memberName(owner, member))
                  .invoke(null));
    } catch (ReflectiveOperationException failure) {
      throw new LinkageError("Cannot inspect packaged F3 controller state", failure);
    }
  }
}
