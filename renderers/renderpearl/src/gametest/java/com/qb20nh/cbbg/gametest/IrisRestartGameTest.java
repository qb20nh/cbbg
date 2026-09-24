package com.qb20nh.cbbg.gametest;

import static com.qb20nh.cbbg.gametest.IrisFixture.*;

import com.mojang.blaze3d.systems.RenderSystem;
import com.qb20nh.cbbg.CbbgClient;
import com.qb20nh.cbbg.compat.iris.IrisCompat;
import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.render.DitherController;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Screenshot;
import org.slf4j.LoggerFactory;

/** Run prepare, exit the JVM, then run verify in the same isolated game directory. */
public final class IrisRestartGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        String phase = System.getProperty("cbbg.test.restart");
        if (!"prepare".equals(phase) && !"verify".equals(phase) && !"control".equals(phase)) {
            throw new AssertionError("Restart test requires prepare, verify or disabled control phase");
        }
        if (!FabricLoader.getInstance().isModLoaded("iris")) {
            throw new AssertionError("Restart fixture requires Iris");
        }
        context.runOnClient(client -> {
            var info = RenderSystem.getDevice().getDeviceInfo();
            LoggerFactory.getLogger("cbbg-test").info("Readback backend={} GPU={} driver={}",
                    info.backendName(), info.name(), info.driverInfo());
            if (!"opengl".equalsIgnoreCase(info.backendName())) {
                throw new AssertionError("Iris restart requires actual OpenGL");
            }
            Object config = iris("getIrisConfig");
            if (!phase.equals("verify")) {
                installPack();
                CbbgConfig.setMode(phase.equals("control")
                        ? CbbgConfig.Mode.DISABLED : CbbgConfig.Mode.ENABLED);
                CbbgConfig.setPixelFormat(CbbgConfig.PixelFormat.RGBA16F);
                invoke(config, "setShaderPackName", String.class, "cbbg-parity");
                invoke(config, "setShadersEnabled", boolean.class, true);
                saveAndReload();
            } else {
                // Inspect startup state before installing a pack or mutating Iris settings.
                try {
                    if (!(Boolean) config.getClass().getMethod("areShadersEnabled").invoke(config)
                            || !Optional.of("cbbg-parity").equals(
                                    config.getClass().getMethod("getShaderPackName").invoke(config))) {
                        throw new AssertionError("Iris shader selection did not survive restart");
                    }
                } catch (ReflectiveOperationException failure) {
                    throw new AssertionError("Could not inspect persisted Iris settings", failure);
                }
                if (CbbgConfig.get().mode() != CbbgConfig.Mode.ENABLED
                        || CbbgConfig.get().pixelFormat() != CbbgConfig.PixelFormat.RGBA16F) {
                    throw new AssertionError("CBBG preferences did not survive restart");
                }
            }
        });
        try (var world = context.worldBuilder().create()) {
            world.getConnection().waitForChunksRender();
            context.waitFor(client -> IrisCompat.isShaderPackActive()
                    && !DitherController.isReady(), 600);
            long stopped = context.computeOnClient(client -> DitherController.getPresentationCount());
            context.waitTicks(5);
            CompletableFuture<Void> capture = new CompletableFuture<>();
            context.runOnClient(client -> {
                if ((Boolean) iris("isFallback")
                        || CbbgClient.getEffectiveMode() != CbbgConfig.Mode.DISABLED
                        || DitherController.getPresentationCount() != stopped
                        || !client.gameRenderer.mainRenderTarget().getColorTexture().getFormat()
                                .name().equals("RGBA8_UNORM")) {
                    throw new AssertionError("Restart shader did not suspend CBBG cleanly");
                }
                Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> {
                    try (image) {
                        int magenta = 0;
                        int total = 0;
                        for (int y = image.getHeight() / 4; y < image.getHeight() * 3 / 4; y++) {
                            for (int x = image.getWidth() / 4; x < image.getWidth() * 3 / 4; x++) {
                                total++;
                                if ((image.getPixel(x, y) & 0xffffff) == 0xff00ff) magenta++;
                            }
                        }
                        if (magenta < total * 0.9) {
                            throw new AssertionError("Persisted shader did not reach the screenshot");
                        }
                        Path evidence = Path.of(System.getProperty("cbbg.test.evidence"));
                        Files.createDirectories(evidence);
                        image.writeToFile(evidence.resolve("iris-restart-" + phase + ".png"));
                        capture.complete(null);
                    } catch (Throwable failure) {
                        capture.completeExceptionally(failure);
                    }
                });
            });
            context.waitFor(client -> capture.isDone(), 200);
            capture.join();
            if (phase.equals("verify")) {
                context.runOnClient(client -> {
                    invoke(iris("getIrisConfig"), "setShadersEnabled", boolean.class, false);
                    saveAndReload();
                });
                context.waitFor(client -> !IrisCompat.isShaderPackActive() && DitherController.isReady()
                        && DitherController.getPresentationCount() > stopped, 600);
                context.runOnClient(client -> {
                    if (CbbgClient.getEffectiveMode() != CbbgConfig.Mode.ENABLED
                            || !client.gameRenderer.mainRenderTarget().getColorTexture().getFormat()
                                    .name().equals("RGBA16_FLOAT")) {
                        throw new AssertionError("Disabling Iris after restart did not restore CBBG");
                    }
                });
            }
        }
        // Prepare intentionally leaves shaders enabled on disk for the next JVM.
    }
}
