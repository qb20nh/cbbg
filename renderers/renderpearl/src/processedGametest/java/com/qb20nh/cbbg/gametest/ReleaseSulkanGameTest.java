package com.qb20nh.cbbg.gametest;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.systems.RenderSystem;
import java.lang.reflect.InvocationTargetException;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** Uses Sulkan's public API and observations of the packaged CBBG mod. */
@NullMarked
public final class ReleaseSulkanGameTest implements FabricClientGameTest {
  @Override
  public void runTest(ClientGameTestContext context) {
    boolean expected =
        java.util.List.of(
                Objects.requireNonNull(System.getProperty("cbbg.test.compat", "none")).split("\\+"))
            .contains("sulkan");
    if (FabricLoader.getInstance().isModLoaded("sulkan") != expected) {
      throw new AssertionError("Sulkan presence does not match the requested fixture");
    }
    ReleaseClient.checkArtifactAndBackend(context);
    boolean vulkan = vulkan(context);
    if (!expected) {
      context.runOnClient(
          client -> {
            if (active()) throw new AssertionError("Absent Sulkan is active");
            checkGate(client, userMode(client), false);
          });
      return;
    }
    Object original =
        context.computeOnClient(
            client -> Objects.requireNonNull(invoke("config", new Class<?>[0])));
    context.runOnClient(client -> checkGate(client, userMode(client), active()));
    try (var world = context.worldBuilder().create()) {
      world.getConnection().waitForChunksRender();
      JsonObject saved = saveSettings(context);
      try {
        ReleaseClient.command(context, "mode set demo");
        for (boolean enabled : new boolean[] {true, false}) {
          select(context, enabled, "__builtin__");
          boolean suspended = enabled && vulkan;
          context.waitFor(client -> active() == suspended, 600);
          context.runOnClient(
              client -> {
                checkGate(client, "DEMO", suspended);
                if (!ReleaseClient.settings().get("mode").getAsString().equals("DEMO")) {
                  throw new AssertionError("Sulkan changed the saved CBBG user mode");
                }
              });
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

  static boolean active() {
    return FabricLoader.getInstance().isModLoaded("sulkan")
        && Objects.requireNonNull((Boolean) invoke("shadersEnabled", new Class<?>[0]));
  }

  static String userMode(Minecraft client) {
    String output = String.join("\n", ReleaseDebugState.read(client));
    var user = Pattern.compile("\\(user=(ENABLED|DISABLED|DEMO)\\)").matcher(output);
    if (!user.find()) throw new AssertionError("Missing user mode in debug entry: " + output);
    return Objects.requireNonNull(user.group(1));
  }

  static void checkGate(Minecraft client, String user, boolean suspended) {
    String output = String.join("\n", ReleaseDebugState.read(client));
    if (!output.contains("mode=" + (suspended ? "DISABLED" : user) + " (user=" + user + ")")
        || !output.contains("sulkan=" + (suspended ? 1 : 0))
        || !output.contains("dis=0")) {
      throw new AssertionError("Sulkan gate differs from the saved user mode: " + output);
    }
  }

  /** A no-op command persists defaults only after startup observations and a live connection. */
  static JsonObject saveSettings(ClientGameTestContext context) {
    String user = context.computeOnClient(ReleaseSulkanGameTest::userMode);
    ReleaseClient.command(context, "mode set " + user.toLowerCase(Locale.ROOT));
    context.waitFor(client -> ReleaseClient.settings().has("stbnDepth"), 600);
    return ReleaseClient.settings().deepCopy();
  }

  static void restoreSettings(ClientGameTestContext context, JsonObject saved) {
    ReleaseClient.command(
        context, "mode set " + saved.get("mode").getAsString().toLowerCase(Locale.ROOT));
    context.waitFor(client -> saved.equals(ReleaseClient.settings()), 600);
  }

  static void select(ClientGameTestContext context, boolean enabled, String pack) {
    CompletableFuture<?> reload =
        context.computeOnClient(
            client ->
                (CompletableFuture<?>)
                    Objects.requireNonNull(
                        invoke(
                            "applySelection",
                            new Class<?>[] {Minecraft.class, boolean.class, String.class},
                            client,
                            enabled,
                            pack)));
    await(context, reload);
    context.waitFor(client -> client.gui.overlay() == null, 600);
    context.waitTicks(5);
  }

  static void restoreConfig(ClientGameTestContext context, Object original) {
    CompletableFuture<?> restore =
        context.computeOnClient(
            client ->
                (CompletableFuture<?>)
                    Objects.requireNonNull(
                        invoke(
                            "applyConfig",
                            new Class<?>[] {Minecraft.class, original.getClass(), boolean.class},
                            client,
                            original,
                            false)));
    await(context, restore);
    context.waitFor(client -> client.gui.overlay() == null, 600);
  }

  static void await(ClientGameTestContext context, CompletableFuture<?> future) {
    context.waitFor(client -> future.isDone(), 1200);
    future.join();
  }

  static @Nullable Object invoke(String name, Class<?>[] arguments, Object... values) {
    try {
      return Class.forName("com.sulkan.shaders.runtime.ShaderRuntime")
          .getMethod(name, arguments)
          .invoke(null, values);
    } catch (ReflectiveOperationException failure) {
      Throwable cause =
          failure instanceof InvocationTargetException invocation ? invocation.getCause() : failure;
      throw new LinkageError("Could not call Sulkan " + name, cause);
    }
  }
}
