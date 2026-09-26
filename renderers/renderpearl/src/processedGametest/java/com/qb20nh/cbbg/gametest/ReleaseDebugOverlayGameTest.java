package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntryStatus;
import net.minecraft.resources.Identifier;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

public final class ReleaseDebugOverlayGameTest implements FabricClientGameTest {
  private static final Identifier ID = Identifier.fromNamespaceAndPath("cbbg", "cbbg");
  private static final Pattern NOISE = Pattern.compile("stbn=(\\d+)/(\\d+)");

  @Override
  public void runTest(ClientGameTestContext context) {
    String originalMode = ReleaseClient.settings().get("mode").getAsString();
    int depth = ReleaseClient.settings().get("stbnDepth").getAsInt();
    boolean visible = context.computeOnClient(client -> client.debugEntries.isOverlayVisible());
    var status = context.computeOnClient(client -> client.debugEntries.getStatus(ID));
    context.runOnClient(
        client -> {
          for (var profile : DebugScreenEntries.PROFILES.values()) {
            if (profile.get(ID) != DebugScreenEntryStatus.IN_OVERLAY) {
              throw new AssertionError("CBBG is absent from a default debug profile");
            }
          }
        });
    try (var world = context.worldBuilder().create()) {
      world.getConnection().waitForChunksRender();
      try {
        for (String mode : new String[] {"ENABLED", "DISABLED", "DEMO"}) {
          ReleaseClient.command(context, "mode set " + mode);
          if (mode.equals("DISABLED")) {
            ReleaseClient.awaitFormat(context, GpuFormat.RGBA8_UNORM);
            ReleaseClient.assertNoDraws(context);
          } else {
            ReleaseClient.awaitDrawAfter(context, ProcessedRenderObservations.draws());
          }
          context.runOnClient(
              client -> {
                String text = output(client);
                String main =
                    client.gameRenderer.mainRenderTarget().getColorTexture().getFormat().name();
                String lightmap = client.gameRenderer.levelLightmap().texture().getFormat().name();
                String backend = RenderSystem.getDevice().getDeviceInfo().backendName();
                checkFramebufferState(client, backend, text);
                var noise = NOISE.matcher(text);
                if (!text.contains("mode=" + mode + " (user=" + mode + ")")
                    || !text.contains("main=" + main)
                    || !text.contains("backend=" + backend)
                    || !text.contains("lm=" + lightmap)
                    || !text.contains("dis=0")
                    || !text.contains("iris=0")
                    || !text.contains("sulkan=0")
                    || !noise.find()) {
                  throw new AssertionError("Incorrect CBBG debug state: " + text);
                }
                int frame = Integer.parseInt(noise.group(1));
                int count = Integer.parseInt(noise.group(2));
                if (mode.equals("DISABLED")
                    ? frame != 0 || count != 0
                    : count != depth || frame < 0 || frame >= count) {
                  throw new AssertionError("Incorrect debug noise sequence: " + text);
                }
                try {
                  Path directory = Path.of(System.getProperty("cbbg.test.evidence"), "debug");
                  Files.createDirectories(directory);
                  Files.writeString(directory.resolve(mode + ".txt"), text + "\n");
                } catch (java.io.IOException failure) {
                  throw new AssertionError("Could not retain debug output", failure);
                }
                client.debugEntries.setStatus(ID, DebugScreenEntryStatus.IN_OVERLAY);
                client.debugEntries.setOverlayVisible(true);
              });
          context.waitTicks(5);
          ReleaseClient.screenshot(context, "debug-" + mode);
        }
      } finally {
        ReleaseClient.command(context, "mode set " + originalMode);
      }
    } finally {
      context.runOnClient(
          client -> {
            client.debugEntries.setStatus(ID, status);
            client.debugEntries.setOverlayVisible(visible);
          });
    }
  }

  private static String output(Minecraft client) {
    var lines = ReleaseDebugState.read(client);
    if (lines.size() != 2) throw new AssertionError("Incomplete CBBG debug output: " + lines);
    return String.join("\n", lines);
  }

  private static void checkFramebufferState(Minecraft client, String backend, String text) {
    if (!backend.equalsIgnoreCase("opengl")) {
      if (!text.contains("fb=n/a srgb=n/a")) {
        throw new AssertionError("Non-GL debug entry reported GL framebuffer state");
      }
      return;
    }
    if (!text.contains("fb=SRGB") && !text.contains("fb=LIN")) {
      throw new AssertionError("Default framebuffer encoding was not identified");
    }
    int draw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
    int read = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
    boolean srgb = GL11.glIsEnabled(GL30.GL_FRAMEBUFFER_SRGB);
    int testDraw = GL30.glGenFramebuffers();
    int testRead = GL30.glGenFramebuffers();
    try {
      GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, testDraw);
      GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, testRead);
      for (boolean enabled : new boolean[] {false, true}) {
        if (enabled) GL11.glEnable(GL30.GL_FRAMEBUFFER_SRGB);
        else GL11.glDisable(GL30.GL_FRAMEBUFFER_SRGB);
        String actual = output(client);
        if (!actual.contains("srgb=" + (enabled ? 1 : 0))
            || GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING) != testDraw
            || GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING) != testRead
            || GL11.glIsEnabled(GL30.GL_FRAMEBUFFER_SRGB) != enabled) {
          throw new AssertionError(
              "Debug query changed bindings/conversion state or reported stale state");
        }
      }
    } finally {
      GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
      GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
      if (srgb) GL11.glEnable(GL30.GL_FRAMEBUFFER_SRGB);
      else GL11.glDisable(GL30.GL_FRAMEBUFFER_SRGB);
      GL30.glDeleteFramebuffers(testDraw);
      GL30.glDeleteFramebuffers(testRead);
    }
  }
}
