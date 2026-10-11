package com.qb20nh.cbbg.gametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.NullMarked;

@NullMarked
public final class ReleaseControlsGameTest implements FabricClientGameTest {
  private static final int WAIT_TICKS = 600;
  private static final int DEFAULT_SIZE = 128;
  private static final int DEFAULT_DEPTH = 64;
  private static final long DEFAULT_SEED = 0;

  @Override
  public void runTest(ClientGameTestContext context) {
    JsonObject original = settings();
    FabricClientCommandSource source = silentSource();
    try (var world = context.worldBuilder().create()) {
      ReleaseViewport.waitForChunks(world);
      try {
        context.runOnClient(
            client -> {
              execute(source, "mode set disabled", 1);
              execute(source, "format set rgba16f", 1);
              execute(source, "stbn size 16", 1);
              execute(source, "stbn depth 8", 1);
              execute(source, "stbn seed 42", 1);
              assertSaved("DISABLED", "RGBA16F", 16, 8, 42, original.get("strength").getAsFloat());

              execute(source, "mode set enabled", 1);
              execute(source, "format set rgba32f", 1);
              assertSaved("ENABLED", "RGBA32F", 16, 8, 42, original.get("strength").getAsFloat());
              execute(source, "mode set demo", 1);
              assertSaved("DEMO", "RGBA32F", 16, 8, 42, original.get("strength").getAsFloat());

              JsonObject beforeInvalid = settings();
              execute(source, "mode set invalid", 0);
              execute(source, "format set invalid", 0);
              execute(source, "stbn size 17", 0);
              execute(source, "stbn depth 9", 0);
              if (!beforeInvalid.equals(settings())) {
                throw new AssertionError("Rejected CBBG commands changed persisted settings");
              }

              execute(source, "stbn reset", 1);
              assertSaved(
                  "DEMO",
                  "RGBA32F",
                  DEFAULT_SIZE,
                  DEFAULT_DEPTH,
                  DEFAULT_SEED,
                  original.get("strength").getAsFloat());
              execute(source, "mode set enabled", 1);
            });
        context.waitFor(
            client -> "ENABLED".equals(settings().get("mode").getAsString()), WAIT_TICKS);
        checkFullscreenAndViewport(context);
      } finally {
        restore(context, source, original);
      }
    }
  }

  private static void checkFullscreenAndViewport(ClientGameTestContext context) {
    int[] originalSize =
        context.computeOnClient(
            client ->
                new int[] {
                  ReleaseViewport.windowWidth(client.getWindow()),
                  ReleaseViewport.windowHeight(client.getWindow()),
                  ReleaseViewport.framebufferWidth(client.getWindow()),
                  ReleaseViewport.framebufferHeight(client.getWindow()),
                  ReleaseViewport.mainTarget(client).width,
                  ReleaseViewport.mainTarget(client).height
                });
    boolean originalFullscreen =
        context.computeOnClient(client -> client.getWindow().isFullscreen());
    int resizedWidth = originalSize[0] == 854 && originalSize[1] == 480 ? 960 : 854;
    int resizedHeight = originalSize[0] == 854 && originalSize[1] == 480 ? 540 : 480;
    try {
      if (originalFullscreen) toggleFullscreen(context, false);
      ReleaseViewport.resizeWindow(context, resizedWidth, resizedHeight);
      context.waitFor(
          client ->
              ReleaseViewport.windowWidth(client.getWindow()) == resizedWidth
                  && ReleaseViewport.windowHeight(client.getWindow()) == resizedHeight
                  && ReleaseViewport.framebufferWidth(client.getWindow()) > 0
                  && ReleaseViewport.framebufferHeight(client.getWindow()) > 0
                  && ReleaseViewport.mainTarget(client).width > 0
                  && ReleaseViewport.mainTarget(client).height > 0
                  && (ReleaseViewport.mainTarget(client).width != originalSize[4]
                      || ReleaseViewport.mainTarget(client).height != originalSize[5]),
          WAIT_TICKS);
      toggleFullscreen(context, true);
      toggleFullscreen(context, false);
    } finally {
      if (context.computeOnClient(client -> client.getWindow().isFullscreen())) {
        toggleFullscreen(context, false);
      }
      ReleaseViewport.resizeWindow(context, originalSize[0], originalSize[1]);
      context.waitFor(
          client ->
              ReleaseViewport.windowWidth(client.getWindow()) == originalSize[0]
                  && ReleaseViewport.windowHeight(client.getWindow()) == originalSize[1]
                  && ReleaseViewport.framebufferWidth(client.getWindow()) > 0
                  && ReleaseViewport.framebufferHeight(client.getWindow()) > 0,
          WAIT_TICKS);
      if (originalFullscreen) toggleFullscreen(context, true);
    }
    if (context.computeOnClient(client -> client.getWindow().isFullscreen()) != originalFullscreen)
      throw new AssertionError("Fullscreen state was not restored");
  }

  private static void toggleFullscreen(ClientGameTestContext context, boolean expected) {
    context.runOnClient(client -> client.getWindow().toggleFullScreen());
    context.waitFor(client -> client.getWindow().isFullscreen() == expected, WAIT_TICKS);
  }

  private static void restore(
      ClientGameTestContext context, FabricClientCommandSource source, JsonObject original) {
    context.runOnClient(
        client -> {
          execute(source, "mode set disabled", 1);
          execute(
              source,
              "format set " + original.get("pixelFormat").getAsString().toLowerCase(Locale.ROOT),
              1);
          execute(source, "stbn size " + original.get("stbnSize").getAsInt(), 1);
          execute(source, "stbn depth " + original.get("stbnDepth").getAsInt(), 1);
          execute(source, "stbn seed " + original.get("stbnSeed").getAsLong(), 1);
          execute(
              source, "mode set " + original.get("mode").getAsString().toLowerCase(Locale.ROOT), 1);
        });
    context.waitFor(client -> original.equals(settings()), WAIT_TICKS);
  }

  private static FabricClientCommandSource silentSource() {
    return (FabricClientCommandSource)
        java.lang.reflect.Proxy.newProxyInstance(
            FabricClientCommandSource.class.getClassLoader(),
            new Class<?>[] {FabricClientCommandSource.class},
            (proxy, method, args) -> {
              if (method.getName().equals("sendFeedback") || method.getName().equals("sendError")) {
                return null;
              }
              throw new AssertionError("Unexpected CBBG command source call: " + method);
            });
  }

  private static void execute(FabricClientCommandSource source, String command, int expected) {
    var dispatcher = ReleaseCommands.getActiveDispatcher();
    if (dispatcher == null) throw new AssertionError("No active client command dispatcher");
    try {
      int result = dispatcher.execute("cbbg " + command, source);
      if (result != expected) {
        throw new AssertionError("Unexpected command result for '" + command + "': " + result);
      }
    } catch (CommandSyntaxException failure) {
      throw new AssertionError("Command failed: " + command, failure);
    }
  }

  private static void assertSaved(
      String mode, String format, int size, int depth, long seed, float strength) {
    JsonObject saved = settings();
    if (!mode.equals(saved.get("mode").getAsString())
        || !format.equals(saved.get("pixelFormat").getAsString())
        || size != saved.get("stbnSize").getAsInt()
        || depth != saved.get("stbnDepth").getAsInt()
        || seed != saved.get("stbnSeed").getAsLong()
        || Float.compare(strength, saved.get("strength").getAsFloat()) != 0) {
      throw new AssertionError("CBBG commands were not persisted as expected: " + saved);
    }
  }

  private static JsonObject settings() {
    Path config = FabricLoader.getInstance().getConfigDir().resolve("cbbg.json");
    try (var reader = Files.newBufferedReader(config)) {
      return JsonParser.parseReader(reader).getAsJsonObject();
    } catch (IOException failure) {
      throw new AssertionError("Could not read persisted CBBG settings: " + config, failure);
    }
  }
}
