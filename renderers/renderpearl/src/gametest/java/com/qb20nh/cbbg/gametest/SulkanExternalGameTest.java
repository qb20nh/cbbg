package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.systems.RenderSystem;
import com.qb20nh.cbbg.CbbgClient;
import com.qb20nh.cbbg.compat.sulkan.SulkanCompat;
import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.render.DitherController;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import org.slf4j.LoggerFactory;

public final class SulkanExternalGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        if (!FabricLoader.getInstance().isModLoaded("sulkan")) {
            throw new AssertionError("External pack test requires Sulkan");
        }
        Rgba8ReadbackGameTest.recordGraphicsContext(context);
        context.runOnClient(client -> {
            var info = RenderSystem.getDevice().getDeviceInfo();
            LoggerFactory.getLogger("cbbg-test").info("Readback backend={} GPU={} driver={}",
                    info.backendName(), info.name(), info.driverInfo());
            if (!"vulkan".equalsIgnoreCase(info.backendName())) {
                throw new AssertionError("External pack test requires Vulkan");
            }
            Path pack = client.gameDirectory.toPath().resolve("shaders/cbbg-native-test");
            try {
                Files.createDirectories(pack);
                for (String name : new String[] {"sulkan.json", "color.fsh"}) {
                    try (var input = getClass().getResourceAsStream("/sulkan-fixture/" + name)) {
                        if (input == null) throw new AssertionError("Missing native pack fixture: " + name);
                        Files.copy(input, pack.resolve(name));
                    }
                }
            } catch (java.io.IOException failure) {
                throw new AssertionError("Could not install native test pack", failure);
            }
            SulkanGameTest.invoke("reloadPacks", new Class<?>[0]);
            CbbgConfig.setMode(CbbgConfig.Mode.DEMO);
        });
        try (var world = context.worldBuilder().create()) {
            world.getConnection().waitForChunksRender();
            for (int reload = 0; reload < 2; reload++) {
                select(context, true);
                context.waitFor(client -> SulkanCompat.isShaderPackActive() && !DitherController.isReady(), 600);
                long stopped = context.computeOnClient(client -> DitherController.getPresentationCount());
                context.waitTicks(5);
                context.runOnClient(client -> {
                    if (CbbgClient.getEffectiveMode() != CbbgConfig.Mode.DISABLED
                            || CbbgConfig.get().mode() != CbbgConfig.Mode.DEMO
                            || DitherController.getPresentationCount() != stopped
                            || DitherController.getStbnFrames() != 0) {
                        throw new AssertionError("External Sulkan pack did not suspend CBBG");
                    }
                });
                capture(context, "external-" + reload, true);
            }
            select(context, false);
            context.waitFor(client -> !SulkanCompat.isShaderPackActive() && DitherController.isReady(), 600);
            capture(context, "disabled", false);
        }
    }

    private static void select(ClientGameTestContext context, boolean enabled) {
        CompletableFuture<?> reload = context.computeOnClient(client ->
                (CompletableFuture<?>) SulkanGameTest.invoke("applySelection",
                        new Class<?>[] {Minecraft.class, boolean.class, String.class},
                        client, enabled, "cbbg-native-test"));
        SulkanGameTest.await(context, reload);
        context.waitFor(client -> client.gui.overlay() == null, 600);
        context.waitTicks(5);
    }

    private static void capture(ClientGameTestContext context, String name, boolean shader) {
        CompletableFuture<Void> capture = new CompletableFuture<>();
        context.runOnClient(client -> Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> {
            try (image) {
                int magenta = 0;
                int total = 0;
                for (int y = image.getHeight() / 4; y < image.getHeight() * 3 / 4; y++) {
                    for (int x = image.getWidth() / 4; x < image.getWidth() * 3 / 4; x++) {
                        total++;
                        int pixel = image.getPixel(x, y);
                        int red = (pixel >>> 16) & 255;
                        int green = (pixel >>> 8) & 255;
                        int blue = pixel & 255;
                        // Minecraft's vignette darkens the shader output after presentation.
                        if (red >= 128 && red == blue && green == 0) magenta++;
                    }
                }
                image.writeToFile(Path.of(System.getProperty("cbbg.test.evidence"), "sulkan-" + name + ".png"));
                if ((magenta > total * 0.9) != shader) {
                    throw new AssertionError("External Sulkan pack screenshot does not match shader state");
                }
                capture.complete(null);
            } catch (Throwable failure) {
                capture.completeExceptionally(failure);
            }
        }));
        context.waitFor(client -> capture.isDone(), 200);
        capture.join();
    }
}
