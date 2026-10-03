package com.qb20nh.cbbg.gametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.NullMarked;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.slf4j.LoggerFactory;

/** Exercises commands and GPU resources through the packaged mod's public behavior. */
@NullMarked
public final class ReleaseLifecycleGameTest implements FabricClientGameTest {
  @Override
  public void runTest(ClientGameTestContext context) {
    checkBackend(context);
    try (var world = context.worldBuilder().create()) {
      world.getClientLevel().waitForChunksRender();
      command(context, "mode set disabled");
      command(context, "stbn size 16");
      command(context, "stbn depth 8");
      command(context, "stbn seed 42");
      command(context, "format set rgba16f");
      awaitFormat(context, GL11.GL_RGBA8);
      noPresentations(context);
      command(context, "stbn generate");
      context.waitFor(client -> cacheExists(16, 8, 42), 600);
      command(context, "mode set enabled");
      awaitFormat(context, GL30.GL_RGBA16F);
      awaitPresentation(context);
      ReleasePixels.check(context, false);
      ReleaseRenderScale.check(context);
      context.runOnClient(
          client -> {
            ReleaseFramebuffers.customTargets();
            ReleaseFramebuffers.blur(client, GL30.GL_RGBA16F);
          });
      screenshot(context, "enabled-rgba16f");

      GpuTexture oldColor =
          context.computeOnClient(client -> client.getMainRenderTarget().getColorTexture());
      command(context, "format set rgba32f");
      awaitFormat(context, GL30.GL_RGBA32F);
      context.runOnClient(
          client -> {
            if (!Objects.requireNonNull(oldColor).isClosed()) {
              throw new AssertionError("Format replacement retained the old texture");
            }
          });
      command(context, "mode set demo");
      awaitPresentation(context);
      ReleasePixels.check(context, true);
      screenshot(context, "demo-rgba32f");

      int[] original =
          context.computeOnClient(
              client -> new int[] {client.getWindow().getWidth(), client.getWindow().getHeight()});
      try {
        context.getInput().resizeWindow(854, 480);
        context.waitFor(
            client ->
                client.getMainRenderTarget().width == 854
                    && client.getMainRenderTarget().height == 480,
            600);
        awaitFormat(context, GL30.GL_RGBA32F);
        awaitPresentation(context);
        screenshot(context, "resized");
      } finally {
        context.getInput().resizeWindow(original[0], original[1]);
        context.waitFor(
            client ->
                client.getMainRenderTarget().width == original[0]
                    && client.getMainRenderTarget().height == original[1],
            600);
      }
      var reload = context.computeOnClient(client -> client.reloadResourcePacks());
      context.waitFor(client -> reload.isDone() && client.getOverlay() == null, 600);
      reload.join();
      awaitPresentation(context);
      command(context, "mode set disabled");
      awaitFormat(context, GL11.GL_RGBA8);
      noPresentations(context);
      context.runOnClient(client -> ReleaseFramebuffers.blur(client, GL11.GL_RGBA8));
      context.runOnClient(
          client -> {
            var output = ReleaseObservations.output();
            if (output != null && !output.texture().isClosed()) {
              throw new AssertionError("Disabling CBBG retained its output texture");
            }
          });
      command(context, "mode set enabled");
      awaitFormat(context, GL30.GL_RGBA32F);
      awaitPresentation(context);
    }
    try (var world = context.worldBuilder().create()) {
      world.getClientLevel().waitForChunksRender();
      awaitPresentation(context);
      screenshot(context, "world-transition");
    }
  }

  static void command(ClientGameTestContext context, String command) {
    context.runOnClient(
        client -> Objects.requireNonNull(client.getConnection()).sendCommand("cbbg " + command));
  }

  private static boolean cacheExists(int size, int depth, long seed) {
    Path cache = FabricLoader.getInstance().getGameDir().resolve(".cbbg");
    try {
      var lines =
          Files.readAllLines(cache.resolve("stbn_" + size + "x" + size + "x" + depth + ".sha256"));
      return lines.size() == depth + 1 && lines.getFirst().equals("# seed " + seed);
    } catch (IOException incomplete) {
      return false;
    }
  }

  static void awaitPresentation(ClientGameTestContext context) {
    long count = ReleaseObservations.presentations();
    context.waitFor(client -> ReleaseObservations.presentations() > count, 600);
  }

  static void noPresentations(ClientGameTestContext context) {
    context.waitTicks(3);
    long count = ReleaseObservations.presentations();
    context.waitTicks(5);
    if (ReleaseObservations.presentations() != count) {
      throw new AssertionError("CBBG presented while disabled");
    }
  }

  static void awaitFormat(ClientGameTestContext context, int expected) {
    context.waitFor(
        client ->
            format(Objects.requireNonNull(client.getMainRenderTarget().getColorTexture()))
                == expected,
        600);
  }

  static int format(GpuTexture texture) {
    var gl = (GlTexture) texture;
    int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
    try {
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, gl.glId());
      return GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT);
    } finally {
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous);
    }
  }

  private static Path evidence() {
    return Path.of(Objects.requireNonNull(System.getProperty("cbbg.test.evidence")));
  }

  private static void screenshot(ClientGameTestContext context, String name) {
    ReleaseScreenshots.capture(
        context,
        name,
        image -> {
          if (image.getWidth() < 1 || image.getHeight() < 1)
            throw new AssertionError("Empty screenshot");
        });
  }

  private static void checkBackend(ClientGameTestContext context) {
    String version =
        FabricLoader.getInstance()
            .getModContainer("cbbg")
            .orElseThrow()
            .getMetadata()
            .getVersion()
            .getFriendlyString();
    if (!version.endsWith("+mc26.1-fabric"))
      throw new AssertionError("Unexpected artifact: " + version);
    context.runOnClient(
        client -> {
          var device = RenderSystem.getDevice();
          String backend = device.getBackendName();
          if (!"opengl".equalsIgnoreCase(backend))
            throw new AssertionError("Unexpected backend: " + backend);
          LoggerFactory.getLogger("cbbg-test")
              .info(
                  "Readback backend={} GPU={} driver={}",
                  backend,
                  device.getRenderer(),
                  device.getVersion());
          JsonObject observed = new JsonObject();
          observed.addProperty("backend", backend.toLowerCase(Locale.ROOT));
          observed.addProperty("gpu", device.getRenderer());
          observed.addProperty("driver", device.getVersion());
          observed.addProperty("modVersion", version);
          observed.addProperty("version", GL11.glGetString(GL11.GL_VERSION));
          observed.addProperty("major", GL11.glGetInteger(GL30.GL_MAJOR_VERSION));
          observed.addProperty("minor", GL11.glGetInteger(GL30.GL_MINOR_VERSION));
          observed.addProperty("flags", GL11.glGetInteger(GL30.GL_CONTEXT_FLAGS));
          var capabilities = GL.getCapabilities();
          observed.addProperty(
              "profileMask",
              capabilities.OpenGL32 ? GL11.glGetInteger(GL32.GL_CONTEXT_PROFILE_MASK) : 0);
          observed.addProperty(
              "profile",
              capabilities.OpenGL32
                      && (GL11.glGetInteger(GL32.GL_CONTEXT_PROFILE_MASK)
                              & GL32.GL_CONTEXT_CORE_PROFILE_BIT)
                          != 0
                  ? "core"
                  : "compatibility");
          observed.addProperty("glslVersion", GL11.glGetString(GL20.GL_SHADING_LANGUAGE_VERSION));
          observed.addProperty("directStateAccess", capabilities.GL_ARB_direct_state_access);
          observed.addProperty("bufferStorage", capabilities.GL_ARB_buffer_storage);
          try {
            Files.createDirectories(evidence());
            Files.writeString(evidence().resolve("graphics-context.json"), observed + "\n");
            Path config = FabricLoader.getInstance().getConfigDir().resolve("cbbg.json");
            try (var reader = Files.newBufferedReader(config)) {
              if (!JsonParser.parseReader(reader).isJsonObject())
                throw new AssertionError("Missing CBBG configuration");
            }
          } catch (IOException failure) {
            throw new AssertionError("Could not record graphics context", failure);
          }
        });
  }
}
