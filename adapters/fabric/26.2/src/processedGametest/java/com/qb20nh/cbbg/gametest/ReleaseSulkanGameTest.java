package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.systems.RenderSystem;
import com.qb20nh.cbbg.CbbgClient;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
public final class ReleaseSulkanGameTest implements FabricClientGameTest {
  @Override
  public void runTest(ClientGameTestContext context) {
    boolean expected =
        List.of(Objects.requireNonNull(System.getProperty("cbbg.test.compat", "none")).split("\\+"))
            .contains("sulkan");
    if (FabricLoader.getInstance().isModLoaded("sulkan") != expected) {
      throw new AssertionError("Sulkan presence differs from the requested configuration");
    }
    if (!expected) return;
    boolean vulkan =
        context.computeOnClient(
            client ->
                RenderSystem.getDevice().getDeviceInfo().backendName().equalsIgnoreCase("vulkan"));
    Object original =
        context.computeOnClient(
            client -> Objects.requireNonNull(invoke("config", new Class<?>[0])));
    String mode = context.computeOnClient(client -> CbbgClient.getUserMode().name());
    try (var world = context.worldBuilder().create()) {
      world.getConnection().waitForChunksRender();
      try {
        ReleaseLifecycleGameTest.command(context, "mode set demo");
        for (boolean enabled : new boolean[] {true, false}) {
          setShadersEnabled(context, enabled);
          boolean suspended = enabled && vulkan;
          context.runOnClient(
              client -> {
                if (!CbbgClient.getUserMode().name().equals("DEMO")
                    || !CbbgClient.getEffectiveMode().name().equals(suspended ? "DISABLED" : "DEMO")
                    || CbbgClient.isEnabled() == suspended) {
                  throw new AssertionError(
                      "Sulkan suspension differs from its backend and shader setting");
                }
              });
        }
      } finally {
        try {
          await(
              context,
              "applyConfig",
              new Class<?>[] {Minecraft.class, original.getClass(), boolean.class},
              original,
              false);
        } finally {
          ReleaseLifecycleGameTest.command(context, "mode set " + mode.toLowerCase(Locale.ROOT));
        }
      }
    }
  }

  static void setShadersEnabled(ClientGameTestContext context, boolean enabled) {
    await(
        context,
        "applySelection",
        new Class<?>[] {Minecraft.class, boolean.class, String.class},
        enabled,
        "__builtin__");
  }

  private static void await(
      ClientGameTestContext context, String method, Class<?>[] types, Object... arguments) {
    CompletableFuture<?> reload =
        context.computeOnClient(
            client -> {
              Object[] values = new Object[arguments.length + 1];
              values[0] = client;
              System.arraycopy(arguments, 0, values, 1, arguments.length);
              return (CompletableFuture<?>) Objects.requireNonNull(invoke(method, types, values));
            });
    context.waitFor(client -> reload.isDone(), 1200);
    reload.join();
    context.waitFor(client -> client.gui.overlay() == null, 600);
  }

  private static @Nullable Object invoke(String method, Class<?>[] types, Object... arguments) {
    try {
      return Class.forName("com.sulkan.shaders.runtime.ShaderRuntime")
          .getMethod(method, types)
          .invoke(null, arguments);
    } catch (ReflectiveOperationException failure) {
      Throwable cause =
          failure instanceof InvocationTargetException invocation ? invocation.getCause() : failure;
      throw new LinkageError("Could not call Sulkan " + method, cause);
    }
  }
}
