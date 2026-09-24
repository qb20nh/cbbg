package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.qb20nh.cbbg.render.Rgba8Readback;
import com.qb20nh.cbbg.render.FloatPipelines;
import com.google.gson.JsonObject;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.Optional;
import java.util.List;
import net.minecraft.client.renderer.RenderPipelines;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import org.slf4j.LoggerFactory;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL32C;

public final class Rgba8ReadbackGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        context.runOnClient(client -> {
            var info = RenderSystem.getDevice().getDeviceInfo();
            LoggerFactory.getLogger("cbbg-test").info("Readback backend={} GPU={} driver={}",
                    info.backendName(), info.name(), info.driverInfo());
            String expected = System.getProperty("cbbg.test.backend");
            if (expected == null || !expected.equalsIgnoreCase(info.backendName())) {
                throw new AssertionError("Requested backend " + expected + ", got " + info.backendName());
            }
            JsonObject observed = new JsonObject();
            observed.addProperty("backend", info.backendName().toLowerCase(java.util.Locale.ROOT));
            if (info.backendName().equalsIgnoreCase("opengl")) {
                var caps = GL.getCapabilities();
                observed.addProperty("version", GL11C.glGetString(GL11C.GL_VERSION));
                if (caps.OpenGL30) {
                    observed.addProperty("major", GL11C.glGetInteger(GL30C.GL_MAJOR_VERSION));
                    observed.addProperty("minor", GL11C.glGetInteger(GL30C.GL_MINOR_VERSION));
                    observed.addProperty("flags", GL11C.glGetInteger(GL30C.GL_CONTEXT_FLAGS));
                }
                if (caps.OpenGL32) {
                    int mask = GL11C.glGetInteger(GL32C.GL_CONTEXT_PROFILE_MASK);
                    observed.addProperty("profileMask", mask);
                    observed.addProperty("profile", (mask & GL32C.GL_CONTEXT_CORE_PROFILE_BIT) != 0
                            ? "core" : (mask & GL32C.GL_CONTEXT_COMPATIBILITY_PROFILE_BIT) != 0
                            ? "compatibility" : "unknown");
                }
                observed.addProperty("directStateAccess", caps.OpenGL45 || caps.GL_ARB_direct_state_access);
                observed.addProperty("bufferStorage", caps.OpenGL44 || caps.GL_ARB_buffer_storage);
                observed.addProperty("textureStorage", caps.OpenGL42 || caps.GL_ARB_texture_storage);
                observed.addProperty("computeShader", caps.OpenGL43 || caps.GL_ARB_compute_shader);
            }
            try {
                Path directory = Path.of(System.getProperty("cbbg.test.evidence"));
                Files.createDirectories(directory);
                Files.writeString(directory.resolve("graphics-context.json"), observed.toString() + "\n");
            } catch (java.io.IOException failure) {
                throw new AssertionError("Could not record the actual graphics context", failure);
            }
        });
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
                var attachments = List.of(new RenderPassDescriptor.Attachment<>(
                        target.getColorTextureView(), Optional.<org.joml.Vector4fc>empty()));
                var first = FloatPipelines.forAttachments(original, attachments);
                var second = FloatPipelines.forAttachments(original, attachments);
                if (first == original || first != second || first.isClosed()) {
                    throw new AssertionError("Expected a live, cached float pipeline variant");
                }
                return first;
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
                        // The production mixins must select the matching float variant.
                        pass.setPipeline(RenderSystem.getCompiledPipeline(RenderPipelines.TRACY_BLIT));
                        RenderSystem.bindDefaultUniforms(pass);
                        pass.setUniform("InSampler", source.getColorTextureView(),
                                RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
                        pass.draw(3, 1, 0, 0);
                    }
                    Rgba8Readback.capture(rendered, downscaleFactor, image -> {
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
