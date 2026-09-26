package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.systems.RenderSystem;
import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.render.DitherController;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntryStatus;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NullMarked;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

@NullMarked
public final class DebugOverlayGameTest implements FabricClientGameTest {
  private static final Identifier ID = Identifier.fromNamespaceAndPath("cbbg", "cbbg");

  @Override
  public void runTest(ClientGameTestContext context) {
    CbbgConfig original = CbbgConfig.get();
    boolean overlayVisible =
        context.computeOnClient(client -> client.debugEntries.isOverlayVisible());
    var originalStatus = context.computeOnClient(client -> client.debugEntries.getStatus(ID));
    context.runOnClient(
        client -> {
          if (DebugScreenEntries.getEntry(ID) == null) {
            throw new AssertionError("CBBG debug entry is missing");
          }
          for (var profile : DebugScreenEntries.PROFILES.values()) {
            if (profile.get(ID) != DebugScreenEntryStatus.IN_OVERLAY) {
              throw new AssertionError("CBBG is absent from a default debug profile");
            }
          }
        });
    try (var world = context.worldBuilder().create()) {
      world.getConnection().waitForChunksRender();
      for (var mode : CbbgConfig.Mode.values()) {
        context.runOnClient(client -> CbbgConfig.setMode(mode));
        context.waitFor(client -> DitherController.isReady() == mode.isActive(), 600);
        context.runOnClient(
            client -> {
              String text = readOutput(client);
              String main =
                  Objects.requireNonNull(client.gameRenderer.mainRenderTarget().getColorTexture())
                      .getFormat()
                      .name();
              String lightmap = client.gameRenderer.levelLightmap().texture().getFormat().name();
              String backend = RenderSystem.getDevice().getDeviceInfo().backendName();
              checkFramebufferState(client, backend, text);
              if (!text.contains("mode=" + mode + " (user=" + mode + ")")
                  || !text.contains("main=" + main)
                  || !text.contains("backend=" + backend)
                  || !text.contains("lm=" + lightmap)
                  || !text.contains("dis=0")
                  || !text.contains("iris=0")
                  || !text.contains(
                      "stbn="
                          + DitherController.getCurrentStbnFrameIndex()
                          + "/"
                          + (mode.isActive() ? 8 : 0))) {
                throw new AssertionError("Incorrect CBBG debug state: " + text);
              }
              try {
                Path evidence =
                    Path.of(
                        Objects.requireNonNull(System.getProperty("cbbg.test.evidence")), "debug");
                Files.createDirectories(evidence);
                Files.writeString(evidence.resolve(mode.name() + ".txt"), text + "\n");
                client.debugEntries.setStatus(ID, DebugScreenEntryStatus.IN_OVERLAY);
                client.debugEntries.setOverlayVisible(true);
              } catch (java.io.IOException failure) {
                throw new AssertionError("Could not retain debug output", failure);
              }
            });
        context.waitTicks(5);
        try {
          Path capture = context.takeScreenshot("cbbg-debug-" + mode.name());
          Files.copy(
              capture,
              Path.of(
                  Objects.requireNonNull(System.getProperty("cbbg.test.evidence")),
                  "debug",
                  mode.name() + ".png"),
              StandardCopyOption.REPLACE_EXISTING);
        } catch (java.io.IOException failure) {
          throw new AssertionError("Could not retain visible debug overlay", failure);
        }
      }
    } finally {
      context.runOnClient(
          client -> {
            CbbgConfig.setMode(original.mode());
            client.debugEntries.setStatus(ID, originalStatus);
            client.debugEntries.setOverlayVisible(overlayVisible);
          });
    }
  }

  static String readOutput(Minecraft client) {
    List<String> lines = new ArrayList<>();
    var displayer =
        (DebugScreenDisplayer)
            Proxy.newProxyInstance(
                DebugScreenDisplayer.class.getClassLoader(),
                new Class<?>[] {DebugScreenDisplayer.class},
                (proxy, method, args) -> {
                  if (!method.getName().equals("addLine")) {
                    throw new AssertionError("Unexpected debug display method " + method);
                  }
                  lines.add((String) Objects.requireNonNull(args)[0]);
                  return null;
                });
    var entry = DebugScreenEntries.getEntry(ID);
    if (entry == null) throw new AssertionError("CBBG debug entry is missing");
    entry.display(displayer, client.level, null, null);
    if (lines.size() != 2) throw new AssertionError("Incomplete CBBG debug output: " + lines);
    return String.join("\n", lines);
  }

  private static void checkFramebufferState(Minecraft client, String backend, String output) {
    if (!backend.equalsIgnoreCase("opengl")) {
      if (!output.contains("fb=n/a srgb=n/a")) {
        throw new AssertionError("Non-GL debug entry claimed GL framebuffer state");
      }
      return;
    }
    if (!output.contains("fb=SRGB") && !output.contains("fb=LIN")) {
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
        String actual = readOutput(client);
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
