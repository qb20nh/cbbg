package com.qb20nh.cbbg.gametest;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

/** Uses Sulkan's public API and observations of the packaged CBBG mod. */
public final class ReleaseSulkanGameTest implements FabricClientGameTest {
  @Override
  public void runTest(ClientGameTestContext context) {
    ReleaseClient.checkArtifactAndBackend(context);
    boolean vulkan = vulkan(context);
    if (!FabricLoader.getInstance().isModLoaded("sulkan")) {
      context.runOnClient(
          client -> {
            if (active()) throw new AssertionError("Absent Sulkan is active");
            checkGate(client, userMode(client), false);
          });
      return;
    }
    Object original = context.computeOnClient(client -> invoke("config", new Class<?>[0]));
    context.runOnClient(client -> checkGate(client, userMode(client), active()));
    try (var world = context.worldBuilder().create()) {
      world.getConnection().waitForChunksRender();
      JsonObject saved = saveSettings(context);
      try {
        ReleaseClient.command(context, "stbn size 16");
        ReleaseClient.command(context, "stbn depth 8");
        ReleaseClient.command(context, "mode set demo");
        for (boolean enabled : new boolean[] {true, false, true, false}) {
          select(context, enabled, "__builtin__");
          boolean suspended = enabled && vulkan;
          context.waitFor(client -> active() == suspended, 600);
          if (suspended) {
            ReleaseClient.awaitFormat(context, GpuFormat.RGBA8_UNORM);
            assertStopped(context, "DEMO");
          } else {
            awaitRendering(context, "DEMO");
          }
          ReleaseClient.screenshot(context, "sulkan-" + enabled);
        }
      } finally {
        try {
          restoreConfig(context, original);
        } finally {
          restoreSettings(context, saved);
        }
      }
    }
  }

  static boolean vulkan(ClientGameTestContext context) {
    return context.computeOnClient(
        client ->
            "vulkan".equalsIgnoreCase(RenderSystem.getDevice().getDeviceInfo().backendName()));
  }

  static void requireVulkan(ClientGameTestContext context) {
    if (!FabricLoader.getInstance().isModLoaded("sulkan")) {
      throw new AssertionError("Sulkan fixture requires Sulkan");
    }
    ReleaseClient.checkArtifactAndBackend(context);
    if (!vulkan(context)) throw new AssertionError("Sulkan fixture requires actual Vulkan");
  }

  static boolean active() {
    return FabricLoader.getInstance().isModLoaded("sulkan")
        && (Boolean) invoke("shadersEnabled", new Class<?>[0]);
  }

  static String userMode(Minecraft client) {
    String output = String.join("\n", ReleaseDebugState.read(client));
    var user = Pattern.compile("\\(user=(ENABLED|DISABLED|DEMO)\\)").matcher(output);
    if (!user.find()) throw new AssertionError("Missing user mode in debug entry: " + output);
    return user.group(1);
  }

  static void checkGate(Minecraft client, String user, boolean suspended) {
    String output = String.join("\n", ReleaseDebugState.read(client));
    if (!output.contains("mode=" + (suspended ? "DISABLED" : user) + " (user=" + user + ")")
        || !output.contains("sulkan=" + (suspended ? 1 : 0))
        || !output.contains("dis=0")) {
      throw new AssertionError("Sulkan gate differs from the saved user mode: " + output);
    }
  }

  static void checkDebug(Minecraft client, String user, boolean suspended) {
    checkGate(client, user, suspended);
    String output = String.join("\n", ReleaseDebugState.read(client));
    var frame = Pattern.compile("stbn=(\\d+)/(\\d+)").matcher(output);
    boolean stopped = suspended || user.equals("DISABLED");
    boolean frames =
        frame.find()
            && (stopped
                ? frame.group(1).equals("0") && frame.group(2).equals("0")
                : Integer.parseInt(frame.group(2))
                        == ReleaseClient.settings().get("stbnDepth").getAsInt()
                    && Integer.parseInt(frame.group(1)) < Integer.parseInt(frame.group(2)));
    GpuFormat format = stopped ? GpuFormat.RGBA8_UNORM : savedFormat();
    JsonObject saved = ReleaseClient.settings();
    if ((saved.has("mode") && !saved.get("mode").getAsString().equals(user))
        || !frames
        || !output.contains("main=" + format.name())
        || client.gameRenderer.mainRenderTarget().getColorTexture().getFormat() != format) {
      throw new AssertionError("Sulkan resources differ from the render state: " + output);
    }
    try {
      Path evidence = Path.of(System.getProperty("cbbg.test.evidence"), "debug");
      Files.createDirectories(evidence);
      Files.writeString(
          evidence.resolve("sulkan-" + suspended + "-" + user + ".txt"), output + "\n");
    } catch (java.io.IOException failure) {
      throw new AssertionError("Could not retain Sulkan debug output", failure);
    }
  }

  static GpuFormat savedFormat() {
    return switch (ReleaseClient.settings().get("pixelFormat").getAsString()) {
      case "RGBA8" -> GpuFormat.RGBA8_UNORM;
      case "RGBA16F" -> GpuFormat.RGBA16_FLOAT;
      case "RGBA32F" -> GpuFormat.RGBA32_FLOAT;
      default -> throw new AssertionError("Unexpected saved pixel format");
    };
  }

  static void assertStopped(ClientGameTestContext context, String user) {
    context.runOnClient(client -> checkDebug(client, user, true));
    long draws = ProcessedRenderObservations.draws();
    long presentations = ProcessedRenderObservations.presentations();
    context.waitTicks(5);
    context.runOnClient(
        client -> {
          checkDebug(client, user, true);
          if (ProcessedRenderObservations.draws() != draws
              || ProcessedRenderObservations.presentations() != presentations) {
            throw new AssertionError("CBBG drew or presented while Sulkan shaders were active");
          }
        });
  }

  static void awaitRendering(ClientGameTestContext context, String user) {
    ReleaseClient.awaitFormat(context, savedFormat());
    long draws = ProcessedRenderObservations.draws();
    long presentations = ProcessedRenderObservations.presentations();
    context.waitFor(
        client ->
            ProcessedRenderObservations.draws() > draws
                && ProcessedRenderObservations.presentations() > presentations,
        600);
    context.runOnClient(
        client -> {
          if (!ReleaseClient.settings().get("mode").getAsString().equals(user)) {
            throw new AssertionError("Sulkan changed the saved CBBG user mode");
          }
          checkDebug(client, user, false);
        });
  }

  /** A no-op command persists defaults only after startup observations and a live connection. */
  static JsonObject saveSettings(ClientGameTestContext context) {
    String user = context.computeOnClient(ReleaseSulkanGameTest::userMode);
    ReleaseClient.command(context, "mode set " + user.toLowerCase(Locale.ROOT));
    context.waitFor(client -> ReleaseClient.settings().has("stbnDepth"), 600);
    return ReleaseClient.settings().deepCopy();
  }

  static void restoreSettings(ClientGameTestContext context, JsonObject saved) {
    ReleaseClient.command(context, "stbn size " + saved.get("stbnSize").getAsInt());
    ReleaseClient.command(context, "stbn depth " + saved.get("stbnDepth").getAsInt());
    ReleaseClient.command(
        context, "format set " + saved.get("pixelFormat").getAsString().toLowerCase(Locale.ROOT));
    ReleaseClient.command(
        context, "mode set " + saved.get("mode").getAsString().toLowerCase(Locale.ROOT));
    context.waitFor(client -> saved.equals(ReleaseClient.settings()), 600);
  }

  static void select(ClientGameTestContext context, boolean enabled, String pack) {
    CompletableFuture<?> reload =
        context.computeOnClient(
            client ->
                (CompletableFuture<?>)
                    invoke(
                        "applySelection",
                        new Class<?>[] {Minecraft.class, boolean.class, String.class},
                        client,
                        enabled,
                        pack));
    await(context, reload);
    context.waitFor(client -> client.gui.overlay() == null, 600);
    context.waitTicks(5);
  }

  static void restoreConfig(ClientGameTestContext context, Object original) {
    CompletableFuture<?> restore =
        context.computeOnClient(
            client ->
                (CompletableFuture<?>)
                    invoke(
                        "applyConfig",
                        new Class<?>[] {Minecraft.class, original.getClass(), boolean.class},
                        client,
                        original,
                        false));
    await(context, restore);
    context.waitFor(client -> client.gui.overlay() == null, 600);
  }

  static void await(ClientGameTestContext context, CompletableFuture<?> future) {
    context.waitFor(client -> future.isDone(), 1200);
    future.join();
  }

  static Object invoke(String name, Class<?>[] arguments, Object... values) {
    try {
      return Class.forName("com.sulkan.shaders.runtime.ShaderRuntime")
          .getMethod(name, arguments)
          .invoke(null, values);
    } catch (ReflectiveOperationException failure) {
      Throwable cause =
          failure instanceof InvocationTargetException invocation ? invocation.getCause() : failure;
      throw new AssertionError("Could not call Sulkan " + name, cause);
    }
  }
}
