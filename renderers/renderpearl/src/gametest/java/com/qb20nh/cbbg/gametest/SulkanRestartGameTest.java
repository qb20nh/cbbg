package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.systems.RenderSystem;
import com.qb20nh.cbbg.CbbgClient;
import com.qb20nh.cbbg.compat.sulkan.SulkanCompat;
import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.render.DitherController;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.slf4j.LoggerFactory;

/** Prepare saves settings; verify checks them in a second client process. */
public final class SulkanRestartGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        String phase = System.getProperty("cbbg.test.restart");
        if (!"prepare".equals(phase) && !"verify".equals(phase) && !"control".equals(phase)) {
            throw new AssertionError("Sulkan restart requires prepare, verify or control");
        }
        if (!FabricLoader.getInstance().isModLoaded("sulkan")) {
            throw new AssertionError("Sulkan restart requires Sulkan");
        }
        Rgba8ReadbackGameTest.recordGraphicsContext(context);
        context.runOnClient(client -> {
            var info = RenderSystem.getDevice().getDeviceInfo();
            LoggerFactory.getLogger("cbbg-test").info("Readback backend={} GPU={} driver={}",
                    info.backendName(), info.name(), info.driverInfo());
            if (!"vulkan".equalsIgnoreCase(info.backendName())) {
                throw new AssertionError("Sulkan restart requires Vulkan");
            }
            if (phase.equals("verify")) {
                Object config = SulkanGameTest.invoke("config", new Class<?>[0]);
                try {
                    if (!(Boolean) config.getClass().getMethod("enabled").invoke(config)
                            || !"__builtin__".equals(config.getClass().getMethod("selectedPackId").invoke(config))) {
                        throw new AssertionError("Sulkan selection did not survive restart");
                    }
                } catch (ReflectiveOperationException failure) {
                    throw new AssertionError("Could not read Sulkan settings", failure);
                }
                if (CbbgConfig.get().mode() != CbbgConfig.Mode.DEMO
                        || CbbgConfig.get().pixelFormat() != CbbgConfig.PixelFormat.RGBA16F) {
                    throw new AssertionError("CBBG settings did not survive restart");
                }
            } else {
                CbbgConfig.setMode(phase.equals("control") ? CbbgConfig.Mode.DISABLED : CbbgConfig.Mode.DEMO);
                CbbgConfig.setPixelFormat(CbbgConfig.PixelFormat.RGBA16F);
            }
        });
        if (!phase.equals("verify")) select(context, true);
        try (var world = context.worldBuilder().create()) {
            world.getConnection().waitForChunksRender();
            context.waitFor(client -> SulkanCompat.isShaderPackActive() && !DitherController.isReady(), 600);
            long stopped = context.computeOnClient(client -> DitherController.getPresentationCount());
            context.waitTicks(5);
            context.runOnClient(client -> {
                if (CbbgClient.getEffectiveMode() != CbbgConfig.Mode.DISABLED
                        || DitherController.getPresentationCount() != stopped
                        || DitherController.getStbnFrames() != 0
                        || !client.gameRenderer.mainRenderTarget().getColorTexture().getFormat()
                                .name().equals("RGBA8_UNORM")) {
                    throw new AssertionError("Sulkan restart did not suspend CBBG cleanly");
                }
            });
            if (phase.equals("verify")) {
                select(context, false);
                context.waitFor(client -> !SulkanCompat.isShaderPackActive() && DitherController.isReady()
                        && DitherController.getPresentationCount() > stopped, 600);
                context.runOnClient(client -> {
                    if (CbbgConfig.get().mode() != CbbgConfig.Mode.DEMO
                            || CbbgClient.getEffectiveMode() != CbbgConfig.Mode.DEMO
                            || !client.gameRenderer.mainRenderTarget().getColorTexture().getFormat()
                                    .name().equals("RGBA16_FLOAT")) {
                        throw new AssertionError("Disabling Sulkan after restart did not restore CBBG");
                    }
                });
            }
        }
    }

    private static void select(ClientGameTestContext context, boolean enabled) {
        CompletableFuture<?> reload = context.computeOnClient(client ->
                (CompletableFuture<?>) SulkanGameTest.invoke("applySelection",
                        new Class<?>[] {Minecraft.class, boolean.class, String.class},
                        client, enabled, "__builtin__"));
        SulkanGameTest.await(context, reload);
    }
}
