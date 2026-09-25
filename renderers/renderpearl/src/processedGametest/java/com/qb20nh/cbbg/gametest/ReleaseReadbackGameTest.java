package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Screenshot;
import net.minecraft.client.renderer.RenderPipelines;

/** Checks float attachment rendering and vanilla screenshot entrypoints against the packaged mod. */
public final class ReleaseReadbackGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        for (GpuFormat format : new GpuFormat[] {GpuFormat.RGBA16_FLOAT, GpuFormat.RGBA32_FLOAT}) {
            checkReadback(context, format, 1);
            checkReadback(context, format, 2);
        }
        checkShaderReload(context);
        checkReadback(context, GpuFormat.RGBA16_FLOAT, 1);
        checkReadback(context, GpuFormat.RGBA32_FLOAT, 1);
    }

    private static void checkShaderReload(ClientGameTestContext context) {
        CompiledRenderPipeline variant = context.computeOnClient(client -> {
            TextureTarget target = new TextureTarget("CBBG reload fixture", 2, 2,
                    GpuFormat.RGBA16_FLOAT, null);
            try {
                var original = RenderSystem.getCompiledPipeline(RenderPipelines.TRACY_BLIT);
                try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
                        .createRenderPass(() -> "CBBG float reload fixture",
                                target.getColorTextureView(), Optional.empty())) {
                    pass.setPipeline(original);
                    var first = ProcessedRenderObservations.selectedPipeline();
                    pass.setPipeline(original);
                    var second = ProcessedRenderObservations.selectedPipeline();
                    if (first == null || first == original || first != second || first.isClosed()) {
                        throw new AssertionError("Expected a live, cached float pipeline variant");
                    }
                    return first;
                }
            } finally {
                target.destroyBuffers();
            }
        });
        CompletableFuture<Void> reload = context.computeOnClient(client -> client.reloadResourcePacks());
        context.waitFor(client -> reload.isDone(), 600);
        reload.join();
        context.runOnClient(client -> {
            if (!variant.isClosed()) {
                throw new AssertionError("Shader reload did not close the old float pipeline");
            }
        });
    }

    private static void checkReadback(ClientGameTestContext context, GpuFormat format,
            int downscaleFactor) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        context.runOnClient(client -> {
            TextureTarget source = new TextureTarget("CBBG float fixture", 2, 2, format, null);
            TextureTarget rendered = new TextureTarget("CBBG float pipeline", 2, 2, format, null);
            try {
                // Bottom row red/green, top row blue/white. Input alpha is zero;
                // vanilla screenshots intentionally produce opaque output.
                int[] components = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 1, 1, 1, 0};
                ByteBuffer pixels = ByteBuffer.allocateDirect(4 * format.blockSize())
                        .order(ByteOrder.nativeOrder());
                for (int value : components) {
                    if (format == GpuFormat.RGBA16_FLOAT) {
                        pixels.putShort((short) (value == 0 ? 0 : 0x3c00));
                    } else {
                        pixels.putFloat(value);
                    }
                }
                pixels.flip();
                RenderSystem.getDevice().createCommandEncoder()
                        .writeToTexture(source.getColorTexture(), pixels, 0, 0, 0, 0, 2, 2);
                try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
                        .createRenderPass(() -> "CBBG float pipeline fixture",
                                rendered.getColorTextureView(), Optional.empty())) {
                    pass.setPipeline(RenderSystem.getCompiledPipeline(RenderPipelines.TRACY_BLIT));
                    RenderSystem.bindDefaultUniforms(pass);
                    pass.setUniform("InSampler", source.getColorTextureView(),
                            RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
                    pass.draw(3, 1, 0, 0);
                }
                Screenshot.takeScreenshot(rendered, downscaleFactor, image -> {
                    try (image) {
                        int size = 2 / downscaleFactor;
                        if (image.getWidth() != size || image.getHeight() != size) {
                            throw new AssertionError("Unexpected screenshot dimensions");
                        }
                        int[] expected = downscaleFactor == 1
                                ? new int[] {0xff0000ff, 0xffffffff, 0xffff0000, 0xff00ff00}
                                : new int[] {0xff7f7f7f};
                        for (int i = 0; i < expected.length; i++) {
                            int actual = image.getPixel(i % size, i / size);
                            if (actual != expected[i]) {
                                throw new AssertionError(format + " pixel " + i + ": expected "
                                        + Integer.toHexString(expected[i]) + ", got "
                                        + Integer.toHexString(actual));
                            }
                        }
                        var evidence = Path.of(System.getProperty("cbbg.test.evidence"))
                                .resolve("readback-" + System.getProperty("cbbg.test.backend")
                                        + "-" + format + "-" + downscaleFactor + ".png");
                        Files.createDirectories(evidence.getParent());
                        image.writeToFile(evidence);
                        result.complete(null);
                    } catch (Throwable failure) {
                        result.completeExceptionally(failure);
                    } finally {
                        rendered.destroyBuffers();
                        source.destroyBuffers();
                    }
                });
            } catch (Throwable failure) {
                rendered.destroyBuffers();
                source.destroyBuffers();
                result.completeExceptionally(failure);
            }
        });
        context.waitFor(client -> result.isDone(), 200);
        result.join();
    }
}
