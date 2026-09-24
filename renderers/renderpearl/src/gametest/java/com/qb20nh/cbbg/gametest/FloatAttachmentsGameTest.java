package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.device.GpuOutOfMemoryException;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.qb20nh.cbbg.config.CbbgConfig.PixelFormat;
import com.qb20nh.cbbg.render.FloatAttachments;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

public final class FloatAttachmentsGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        context.runOnClient(client -> {
            TextureTarget namedLikeMain = new TextureTarget("Main", 2, 2, GpuFormat.RGBA8_UNORM, null);
            try {
                if (namedLikeMain.getColorTexture().getFormat() != GpuFormat.RGBA8_UNORM) {
                    throw new AssertionError("Upgraded a non-main target based on its label");
                }
            } finally {
                namedLikeMain.destroyBuffers();
            }
            for (int rejected = 0; rejected <= 3; rejected++) {
                int failures = rejected;
                List<GpuFormat> attempts = new ArrayList<>();
                GpuDevice actual = RenderSystem.getDevice();
                GpuDevice device = (GpuDevice) Proxy.newProxyInstance(GpuDevice.class.getClassLoader(),
                        new Class<?>[] {GpuDevice.class}, (proxy, method, args) -> {
                            if (method.getName().equals("createTexture")) {
                                attempts.add((GpuFormat) args[2]);
                                if (attempts.size() <= failures) {
                                    throw new GpuOutOfMemoryException("Injected allocation failure " + attempts.size());
                                }
                            }
                            try {
                                return method.invoke(actual, args);
                            } catch (InvocationTargetException failure) {
                                throw failure.getCause();
                            }
                        });
                try (GpuTexture texture = FloatAttachments.create(device, () -> "CBBG fallback fixture",
                        15, PixelFormat.RGBA32F, 2, 2, 1, 1)) {
                    GpuFormat expected = switch (failures) {
                        case 0 -> GpuFormat.RGBA32_FLOAT;
                        case 1 -> GpuFormat.RGBA16_FLOAT;
                        case 2 -> GpuFormat.RGBA8_UNORM;
                        default -> throw new AssertionError("Expected terminal allocation failure");
                    };
                    if (texture.getFormat() != expected || attempts.size() != failures + 1) {
                        throw new AssertionError("Wrong precision fallback: " + attempts);
                    }
                } catch (GpuOutOfMemoryException failure) {
                    if (failures != 3 || failure.getSuppressed().length != 2) {
                        throw new AssertionError("Lost allocation failure evidence", failure);
                    }
                }
                List<GpuFormat> expectedOrder = List.of(GpuFormat.RGBA32_FLOAT,
                        GpuFormat.RGBA16_FLOAT, GpuFormat.RGBA8_UNORM);
                if (!attempts.equals(expectedOrder.subList(0, Math.min(failures + 1, 3)))) {
                    throw new AssertionError("Wrong allocation order: " + attempts);
                }
            }
        });
    }
}
