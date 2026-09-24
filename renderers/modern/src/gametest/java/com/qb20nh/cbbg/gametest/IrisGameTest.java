package com.qb20nh.cbbg.gametest;

import static com.qb20nh.cbbg.gametest.IrisFixture.*;

import com.qb20nh.cbbg.CbbgClient;
import com.qb20nh.cbbg.compat.iris.IrisCompat;
import com.qb20nh.cbbg.compat.renderscale.RenderScaleCompat;
import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.render.DitherController;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Screenshot;

public final class IrisGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        boolean expected = java.util.List.of(System.getProperty("cbbg.test.compat", "none").split("\\+"))
                .contains("iris");
        if (FabricLoader.getInstance().isModLoaded("iris") != expected) {
            throw new AssertionError("Iris presence does not match the requested fixture");
        }
        if (!expected) {
            return;
        }
        if (!FabricLoader.getInstance().isModLoaded("sodium")) {
            throw new AssertionError("Iris fixture is missing Sodium");
        }
        installPack();
        CbbgConfig original = CbbgConfig.get();
        float originalScale = context.computeOnClient(client -> RenderScaleCompat.isLoaded()
                ? RenderScaleTestAccess.setShaderTestScale(0.5f) : 1);
        try (var world = context.worldBuilder().create()) {
            world.getConnection().waitForChunksRender();
            context.runOnClient(client -> {
                CbbgConfig.setMode(CbbgConfig.Mode.ENABLED);
                CbbgConfig.setPixelFormat(CbbgConfig.PixelFormat.RGBA16F);
            });
            context.waitFor(client -> DitherController.isReady(), 600);
            context.runOnClient(client -> {
                Object config = iris("getIrisConfig");
                invoke(config, "setShaderPackName", String.class, "cbbg-parity");
                invoke(config, "setShadersEnabled", boolean.class, true);
                saveAndReload();
            });
            context.waitFor(client -> IrisCompat.isShaderPackActive() && !DitherController.isReady()
                    && client.gameRenderer.mainRenderTarget().getColorTexture().getFormat()
                            .name().equals("RGBA8_UNORM"), 600);
            long stopped = context.computeOnClient(client -> DitherController.getPresentationCount());
            context.waitTicks(5);
            CompletableFuture<Void> capture = new CompletableFuture<>();
            context.runOnClient(client -> {
                if (CbbgConfig.get().mode() != CbbgConfig.Mode.ENABLED
                        || CbbgClient.getEffectiveMode() != CbbgConfig.Mode.DISABLED
                        || DitherController.getPresentationCount() != stopped) {
                    throw new AssertionError("Iris did not suspend CBBG without changing user settings");
                }
                if ((Boolean) iris("isFallback")) {
                    throw new AssertionError("Iris fell back instead of rendering the fixture shaderpack");
                }
                checkDebug(client, CbbgConfig.Mode.ENABLED, true);
                if (RenderScaleCompat.isLoaded()) {
                    RenderScaleTestAccess.assertShaderTargetFormat(
                            client.gameRenderer.mainRenderTarget(), "RGBA8_UNORM");
                }
                Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> {
                    try (image) {
                        int magenta = 0;
                        int total = 0;
                        for (int y = image.getHeight() / 4; y < image.getHeight() * 3 / 4; y++) {
                            for (int x = image.getWidth() / 4; x < image.getWidth() * 3 / 4; x++) {
                                total++;
                                if ((image.getPixel(x, y) & 0xffffff) == 0xff00ff) {
                                    magenta++;
                                }
                            }
                        }
                        if (magenta < total * 0.9) {
                            throw new AssertionError("The fixture's final shader did not reach the screenshot");
                        }
                        Path evidence = Path.of(System.getProperty("cbbg.test.evidence"));
                        Files.createDirectories(evidence);
                        image.writeToFile(evidence.resolve("iris-active-"
                                + System.getProperty("cbbg.test.backend") + ".png"));
                        capture.complete(null);
                    } catch (Throwable failure) {
                        capture.completeExceptionally(failure);
                    }
                });
                CbbgConfig.setMode(CbbgConfig.Mode.DEMO);
                if (CbbgClient.getEffectiveMode() != CbbgConfig.Mode.DISABLED) {
                    throw new AssertionError("Demo mode bypassed the Iris gate");
                }
                checkDebug(client, CbbgConfig.Mode.DEMO, true);
            });
            context.waitFor(client -> capture.isDone(), 200);
            capture.join();
            context.runOnClient(client -> {
                invoke(iris("getIrisConfig"), "setShadersEnabled", boolean.class, false);
                saveAndReload();
            });
            context.waitFor(client -> !IrisCompat.isShaderPackActive() && DitherController.isReady()
                    && DitherController.getPresentationCount() > stopped, 600);
            context.runOnClient(client -> {
                if (CbbgClient.getEffectiveMode() != CbbgConfig.Mode.DEMO
                        || !client.gameRenderer.mainRenderTarget().getColorTexture().getFormat()
                                .name().equals("RGBA16_FLOAT")) {
                    throw new AssertionError("CBBG did not restore the user's mode and float attachment");
                }
                if (RenderScaleCompat.isLoaded()) {
                    RenderScaleTestAccess.assertShaderTargetFormat(
                            client.gameRenderer.mainRenderTarget(), "RGBA16_FLOAT");
                }
                checkDebug(client, CbbgConfig.Mode.DEMO, false);
            });
        } finally {
            context.runOnClient(client -> {
                invoke(iris("getIrisConfig"), "setShadersEnabled", boolean.class, false);
                saveAndReload();
                CbbgConfig.setMode(original.mode());
                CbbgConfig.setPixelFormat(original.pixelFormat());
                if (RenderScaleCompat.isLoaded()) {
                    RenderScaleTestAccess.setShaderTestScale(originalScale);
                }
            });
        }
    }

    private static void checkDebug(net.minecraft.client.Minecraft client,
            CbbgConfig.Mode user, boolean active) {
        String output = DebugOverlayGameTest.readOutput(client);
        var effective = active ? CbbgConfig.Mode.DISABLED : user;
        String format = active ? "RGBA8_UNORM" : "RGBA16_FLOAT";
        String frame = active ? "0/0" : DitherController.getCurrentStbnFrameIndex() + "/8";
        if (!output.contains("mode=" + effective + " (user=" + user + ")")
                || !output.contains("iris=" + (active ? 1 : 0))
                || !output.contains("dis=0") || !output.contains("main=" + format)
                || !output.contains("stbn=" + frame)) {
            throw new AssertionError("Iris debug state differs from the render state: " + output);
        }
        try {
            Path evidence = Path.of(System.getProperty("cbbg.test.evidence"), "debug");
            Files.createDirectories(evidence);
            Files.writeString(evidence.resolve("iris-" + active + "-" + user + ".txt"), output + "\n");
        } catch (java.io.IOException failure) {
            throw new AssertionError("Could not retain Iris debug output", failure);
        }
    }

}
