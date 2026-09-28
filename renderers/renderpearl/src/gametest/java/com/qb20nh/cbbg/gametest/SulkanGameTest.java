package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.systems.RenderSystem;
import com.qb20nh.cbbg.CbbgClient;
import com.qb20nh.cbbg.compat.sulkan.SulkanCompat;
import com.qb20nh.cbbg.config.CbbgConfig;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.LoggerFactory;

@NullMarked
public final class SulkanGameTest implements FabricClientGameTest {
  @Override
  public void runTest(ClientGameTestContext context) {
    boolean expected =
        java.util.List.of(
                Objects.requireNonNull(System.getProperty("cbbg.test.compat", "none")).split("\\+"))
            .contains("sulkan");
    if (FabricLoader.getInstance().isModLoaded("sulkan") != expected) {
      throw new AssertionError("Sulkan presence does not match the requested fixture");
    }
    Rgba8ReadbackGameTest.recordGraphicsContext(context);
    boolean vulkan =
        context.computeOnClient(
            client -> {
              var info = RenderSystem.getDevice().getDeviceInfo();
              LoggerFactory.getLogger("cbbg-test")
                  .info(
                      "Readback backend={} GPU={} driver={}",
                      info.backendName(),
                      info.name(),
                      info.driverInfo());
              return "vulkan".equalsIgnoreCase(info.backendName());
            });
    if (!expected) {
      context.runOnClient(
          client -> {
            if (SulkanCompat.isShaderPackActive())
              throw new AssertionError("Absent Sulkan is active");
          });
      return;
    }
    Object original =
        context.computeOnClient(
            client -> Objects.requireNonNull(invoke("config", new Class<?>[0])));
    CbbgConfig config = CbbgConfig.get();
    context.runOnClient(client -> CbbgConfig.setMode(CbbgConfig.Mode.DEMO));
    try (var world = context.worldBuilder().create()) {
      world.getConnection().waitForChunksRender();
      for (boolean enabled : new boolean[] {true, false}) {
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
                                "__builtin__")));
        await(context, reload);
        boolean suspended = enabled && vulkan;
        context.waitFor(
            client ->
                SulkanCompat.isShaderPackActive() == suspended
                    && Objects.requireNonNull((Boolean) invoke("shadersEnabled", new Class<?>[0]))
                        == suspended,
            600);
        context.runOnClient(
            client -> {
              var expectedMode = suspended ? CbbgConfig.Mode.DISABLED : CbbgConfig.Mode.DEMO;
              if (CbbgClient.getEffectiveMode() != expectedMode
                  || CbbgConfig.get().mode() != CbbgConfig.Mode.DEMO) {
                throw new AssertionError(
                    "Sulkan changed the saved mode or effective mode is wrong");
              }
            });
      }
    } finally {
      try {
        CompletableFuture<?> restore =
            context.computeOnClient(
                client ->
                    (CompletableFuture<?>)
                        Objects.requireNonNull(
                            invoke(
                                "applyConfig",
                                new Class<?>[] {
                                  Minecraft.class, original.getClass(), boolean.class
                                },
                                client,
                                original,
                                false)));
        await(context, restore);
      } finally {
        context.runOnClient(client -> CbbgConfig.setMode(config.mode()));
      }
    }
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
      throw new LinkageError("Could not call Sulkan " + name, failure);
    }
  }
}
