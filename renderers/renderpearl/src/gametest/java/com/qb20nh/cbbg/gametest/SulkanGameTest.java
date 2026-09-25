package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.systems.RenderSystem;
import com.qb20nh.cbbg.CbbgClient;
import com.qb20nh.cbbg.compat.sulkan.SulkanCompat;
import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.render.DitherController;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.slf4j.LoggerFactory;

public final class SulkanGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        Rgba8ReadbackGameTest.recordGraphicsContext(context);
        boolean vulkan = context.computeOnClient(client -> {
            var info = RenderSystem.getDevice().getDeviceInfo();
            LoggerFactory.getLogger("cbbg-test").info("Readback backend={} GPU={} driver={}",
                    info.backendName(), info.name(), info.driverInfo());
            return "vulkan".equalsIgnoreCase(info.backendName());
        });
        if (!FabricLoader.getInstance().isModLoaded("sulkan")) {
            context.runOnClient(client -> {
                if (SulkanCompat.isShaderPackActive()) throw new AssertionError("Absent Sulkan is active");
            });
            return;
        }
        Object original = context.computeOnClient(client -> invoke("config", new Class<?>[0]));
        CbbgConfig config = CbbgConfig.get();
        context.runOnClient(client -> {
            CbbgConfig.setStbnSize(16);
            CbbgConfig.setStbnDepth(8);
            CbbgConfig.setMode(CbbgConfig.Mode.DEMO);
        });
        try (var world = context.worldBuilder().create()) {
            world.getConnection().waitForChunksRender();
            for (boolean enabled : new boolean[] {true, false, true, false}) {
                CompletableFuture<?> reload = context.computeOnClient(client ->
                        (CompletableFuture<?>) invoke("applySelection",
                                new Class<?>[] {Minecraft.class, boolean.class, String.class},
                                client, enabled, "__builtin__"));
                await(context, reload);
                boolean suspended = enabled && vulkan;
                context.waitFor(client -> SulkanCompat.isShaderPackActive() == suspended
                        && DitherController.isReady() != suspended, 600);
                context.runOnClient(client -> {
                    var expected = suspended ? CbbgConfig.Mode.DISABLED : CbbgConfig.Mode.DEMO;
                    if (CbbgClient.getEffectiveMode() != expected
                            || CbbgConfig.get().mode() != CbbgConfig.Mode.DEMO) {
                        throw new AssertionError("Sulkan changed the saved mode or effective mode is wrong");
                    }
                    if (suspended && (DitherController.getStbnFrames() != 0
                            || !client.gameRenderer.mainRenderTarget().getColorTexture()
                                    .getFormat().name().equals("RGBA8_UNORM"))) {
                        throw new AssertionError("CBBG resources or float main target remain active");
                    }
                });
                if (suspended) {
                    long count = context.computeOnClient(client -> DitherController.getPresentationCount());
                    context.waitTicks(5);
                    context.runOnClient(client -> {
                        if (DitherController.getPresentationCount() != count) {
                            throw new AssertionError("CBBG presented while Sulkan shaders were active");
                        }
                    });
                }
                try {
                    Path image = context.takeScreenshot("sulkan-" + enabled);
                    Files.copy(image, Path.of(System.getProperty("cbbg.test.evidence"),
                            "sulkan-" + enabled + ".png"), StandardCopyOption.REPLACE_EXISTING);
                } catch (java.io.IOException failure) {
                    throw new AssertionError("Could not save Sulkan screenshot", failure);
                }
            }
        } finally {
            try {
                CompletableFuture<?> restore = context.computeOnClient(client ->
                        (CompletableFuture<?>) invoke("applyConfig",
                                new Class<?>[] {Minecraft.class, original.getClass(), boolean.class},
                                client, original, false));
                await(context, restore);
            } finally {
                context.runOnClient(client -> {
                    CbbgConfig.setMode(config.mode());
                    CbbgConfig.setStbnSize(config.stbnSize());
                    CbbgConfig.setStbnDepth(config.stbnDepth());
                });
            }
        }
    }

    private static void await(ClientGameTestContext context, CompletableFuture<?> future) {
        context.waitFor(client -> future.isDone(), 1200);
        future.join();
    }

    private static Object invoke(String name, Class<?>[] arguments, Object... values) {
        try {
            return Class.forName("com.sulkan.shaders.runtime.ShaderRuntime")
                    .getMethod(name, arguments).invoke(null, values);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Could not call Sulkan " + name, failure);
        }
    }
}
