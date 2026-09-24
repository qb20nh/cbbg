package com.qb20nh.cbbg.gametest;

import com.mojang.renderpearl.api.GpuFormat;
import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.config.CbbgConfig.Mode;
import com.qb20nh.cbbg.config.CbbgConfig.PixelFormat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;

public final class MainTargetGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        if (!FabricLoader.getInstance().isModLoaded("cbbg")) {
            throw new AssertionError("Production adapter is not loaded");
        }
        CbbgConfig original = CbbgConfig.get();
        try (var world = context.worldBuilder().create()) {
            world.getConnection().waitForChunksRender();
            checkFormat(context, GpuFormat.RGBA16_FLOAT);
            recreate(context, Mode.ENABLED, PixelFormat.RGBA32F, GpuFormat.RGBA32_FLOAT);
            recreate(context, Mode.DISABLED, PixelFormat.RGBA32F, GpuFormat.RGBA8_UNORM);
            recreate(context, Mode.ENABLED, PixelFormat.RGBA16F, GpuFormat.RGBA16_FLOAT);
            Path screenshot = context.takeScreenshot("cbbg-float-world");
            Path evidence = Path.of(System.getProperty("cbbg.test.evidence"))
                    .resolve("world-" + System.getProperty("cbbg.test.backend") + ".png");
            try {
                Files.createDirectories(evidence.getParent());
                Files.copy(screenshot, evidence, StandardCopyOption.REPLACE_EXISTING);
                context.runOnClient(client -> {
                    for (String side : new String[] {"left", "right"}) {
                        String key = "cbbg.hud.demo." + side;
                        if (Component.translatable(key).getString().equals(key)) {
                            throw new AssertionError("Missing demo label translation: " + key);
                        }
                    }
                    CbbgConfig.setMode(Mode.DEMO);
                });
                context.waitTicks(3);
                Path demo = context.takeScreenshot("cbbg-demo-world");
                Files.copy(demo, evidence.resolveSibling("demo-"
                        + System.getProperty("cbbg.test.backend") + ".png"),
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.io.IOException failure) {
                throw new AssertionError("Could not retain world screenshot", failure);
            }
        } finally {
            context.runOnClient(client -> {
                CbbgConfig.setMode(original.mode());
                CbbgConfig.setPixelFormat(original.pixelFormat());
                var main = client.gameRenderer.mainRenderTarget();
                main.resize(main.width, main.height);
            });
        }
    }

    private static void recreate(ClientGameTestContext context, Mode mode, PixelFormat requested,
            GpuFormat expected) {
        context.runOnClient(client -> {
            CbbgConfig.setMode(mode);
            CbbgConfig.setPixelFormat(requested);
            var main = client.gameRenderer.mainRenderTarget();
            var old = main.getColorTexture();
            main.resize(main.width, main.height);
            if (old == null || !old.isClosed()) {
                throw new AssertionError("Resize retained the old color attachment");
            }
        });
        context.waitTicks(3);
        checkFormat(context, expected);
    }

    private static void checkFormat(ClientGameTestContext context, GpuFormat expected) {
        context.runOnClient(client -> {
            var main = client.gameRenderer.mainRenderTarget();
            var color = main.getColorTexture();
            if (color == null || color.getFormat() != expected || main.getDepthTexture() == null) {
                throw new AssertionError("Expected main attachment " + expected);
            }
        });
    }
}
