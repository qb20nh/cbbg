package com.qb20nh.cbbg.gametest;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.resource.CrossFrameResourcePool;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.resource.RenderTargetDescriptor;
import com.mojang.blaze3d.resource.ResourceDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import java.nio.ByteOrder;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.resources.Identifier;
import org.joml.Vector4f;

/** Exercises vanilla post chains against the optimized packaged mod through public GPU APIs. */
public final class ReleaseMenuBlurGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        JsonObject original = ReleaseClient.settings();
        try (var world = context.worldBuilder().create()) {
            world.getConnection().waitForChunksRender();
            var pool = context.computeOnClient(client -> new CrossFrameResourcePool(3));
            try {
                setMode(context, "enabled");
                check(context, pool, GpuFormat.RGBA16_FLOAT, true);
                check(context, pool, GpuFormat.RGBA32_FLOAT, true);
                check(context, pool, GpuFormat.RGBA16_FLOAT, true);
                checkFailureAndNesting(context);
                check(context, pool, GpuFormat.RGBA16_FLOAT, true);
                setMode(context, "disabled");
                check(context, pool, GpuFormat.RGBA16_FLOAT, false);
            } finally {
                context.runOnClient(client -> pool.close());
                setMode(context, original.get("mode").getAsString().toLowerCase(Locale.ROOT));
            }
        }
    }

    private static void setMode(ClientGameTestContext context, String mode) {
        ReleaseClient.command(context, "mode set " + mode);
        context.waitFor(client -> mode.equalsIgnoreCase(
                ReleaseClient.settings().get("mode").getAsString()), 600);
        context.waitTicks(3);
    }

    private static PostChain chain(net.minecraft.client.Minecraft client, String name) {
        var chain = client.getShaderManager().getPostChain(
                Identifier.withDefaultNamespace(name), LevelTargetBundle.MAIN_TARGETS);
        if (chain == null) {
            throw new AssertionError("Vanilla " + name + " chain is unavailable");
        }
        return chain;
    }

    private static void assertFormat(ResourceDescriptor<?> descriptor, GpuFormat expected) {
        if (!(descriptor instanceof RenderTargetDescriptor rt)
                || rt.color() == null || rt.color().format() != expected) {
            throw new AssertionError("Expected post-chain descriptor " + expected + ", got " + descriptor);
        }
    }

    // addToFrame deliberately bypasses process's scope entry. Its descriptors expose the
    // current scope without linking to production classes or allocating any GPU resources.
    private static void probeScope(PostChain blur, RenderTarget target, GpuFormat expected) {
        var frame = new FrameGraphBuilder();
        var handle = frame.importExternal("CBBG scope probe", target);
        blur.addToFrame(frame, target.width, target.height,
                PostChain.TargetBundle.of(PostChain.MAIN_TARGET_ID, handle));
        RuntimeException observed = new RuntimeException("Descriptor probe complete");
        try {
            frame.execute(new GraphicsResourceAllocator() {
                @Override
                public <T> T acquire(ResourceDescriptor<T> descriptor) {
                    assertFormat(descriptor, expected);
                    throw observed;
                }
                @Override
                public <T> void release(ResourceDescriptor<T> descriptor, T resource) {
                    throw new AssertionError("Descriptor probe unexpectedly acquired a resource");
                }
            });
            throw new AssertionError("Scope probe did not observe a descriptor");
        } catch (RuntimeException failure) {
            if (failure != observed) throw failure;
        }
    }

    private static void checkFailureAndNesting(ClientGameTestContext context) {
        context.runOnClient(client -> {
            var blur = chain(client, "blur");
            var other = chain(client, "creeper");
            var target = new TextureTarget("CBBG blur failure", 4, 4, GpuFormat.RGBA32_FLOAT, null);
            try {
                RenderSystem.getDevice().createCommandEncoder().clearColorTexture(
                        target.getColorTexture(), new Vector4f(0.5f, 0.5f, 0.5f, 1));
                RuntimeException injected = new RuntimeException("Injected blur allocation failure");
                try {
                    blur.process(target, new GraphicsResourceAllocator() {
                        @Override
                        public <T> T acquire(ResourceDescriptor<T> descriptor) {
                            assertFormat(descriptor, GpuFormat.RGBA32_FLOAT);
                            throw injected;
                        }
                        @Override
                        public <T> void release(ResourceDescriptor<T> descriptor, T resource) {
                            throw new AssertionError("Failure fixture unexpectedly acquired a resource");
                        }
                    });
                    throw new AssertionError("Expected blur allocation failure");
                } catch (RuntimeException failure) {
                    if (failure != injected) throw failure;
                }
                probeScope(blur, target, GpuFormat.RGBA8_UNORM);
                var unrelated = new TextureTarget("CBBG unrelated chain", 4, 4, GpuFormat.RGBA8_UNORM, null);
                try {
                    RenderSystem.getDevice().createCommandEncoder().clearColorTexture(
                            unrelated.getColorTexture(), new Vector4f(0.5f, 0.5f, 0.5f, 1));
                    processUnrelated(other, blur, unrelated, target);
                    probeScope(blur, target, GpuFormat.RGBA8_UNORM);
                    int[] acquisitions = {0};
                    blur.process(target, new GraphicsResourceAllocator() {
                        @Override
                        public <T> T acquire(ResourceDescriptor<T> descriptor) {
                            assertFormat(descriptor, GpuFormat.RGBA32_FLOAT);
                            if (acquisitions[0]++ == 0) {
                                processUnrelated(other, blur, unrelated, target);
                                probeScope(blur, target, GpuFormat.RGBA32_FLOAT);
                            }
                            return GraphicsResourceAllocator.UNPOOLED.acquire(descriptor);
                        }
                        @Override
                        public <T> void release(ResourceDescriptor<T> descriptor, T resource) {
                            GraphicsResourceAllocator.UNPOOLED.release(descriptor, resource);
                        }
                    });
                    if (acquisitions[0] == 0) throw new AssertionError("Nested blur did not allocate targets");
                    probeScope(blur, target, GpuFormat.RGBA8_UNORM);
                } finally {
                    unrelated.destroyBuffers();
                }
            } finally {
                target.destroyBuffers();
            }
        });
    }

    private static void processUnrelated(PostChain other, PostChain blur,
            RenderTarget unrelated, RenderTarget probeTarget) {
        int[] acquisitions = {0};
        other.process(unrelated, new GraphicsResourceAllocator() {
            @Override
            public <T> T acquire(ResourceDescriptor<T> descriptor) {
                assertFormat(descriptor, GpuFormat.RGBA8_UNORM);
                probeScope(blur, probeTarget, GpuFormat.RGBA8_UNORM);
                acquisitions[0]++;
                return GraphicsResourceAllocator.UNPOOLED.acquire(descriptor);
            }
            @Override
            public <T> void release(ResourceDescriptor<T> descriptor, T resource) {
                GraphicsResourceAllocator.UNPOOLED.release(descriptor, resource);
            }
        });
        if (acquisitions[0] == 0) throw new AssertionError("Unrelated chain did not allocate targets");
    }

    private static void check(ClientGameTestContext context, CrossFrameResourcePool pool,
            GpuFormat format, boolean enabled) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        context.runOnClient(client -> {
            var device = RenderSystem.getDevice();
            var target = new TextureTarget("CBBG blur precision", 4, 4, format, null);
            AtomicBoolean targetClosed = new AtomicBoolean();
            Runnable closeTarget = () -> {
                if (targetClosed.compareAndSet(false, true)) target.destroyBuffers();
            };
            try {
                var buffer = device.createBuffer(() -> "CBBG blur readback", 9, 16L * format.blockSize());
                AtomicBoolean cleaned = new AtomicBoolean();
                Runnable cleanup = () -> {
                    if (cleaned.compareAndSet(false, true)) {
                        try {
                            buffer.close();
                        } finally {
                            closeTarget.run();
                        }
                    }
                };
                try {
                    device.createCommandEncoder().clearColorTexture(target.getColorTexture(),
                            new Vector4f(1.0f / 1024, 3.0f / 1024, 5.0f / 1024, 1));
                    var blur = chain(client, "blur");
                    int[] acquisitions = {0};
                    blur.process(target, new GraphicsResourceAllocator() {
                        @Override
                        public <T> T acquire(ResourceDescriptor<T> descriptor) {
                            GpuFormat expected = enabled ? format : GpuFormat.RGBA8_UNORM;
                            assertFormat(descriptor, expected);
                            T resource = pool.acquire(descriptor);
                            if (((RenderTarget) resource).getColorTexture().getFormat() != expected) {
                                pool.release(descriptor, resource);
                                throw new AssertionError("Reused pooled target has the wrong format");
                            }
                            acquisitions[0]++;
                            return resource;
                        }
                        @Override
                        public <T> void release(ResourceDescriptor<T> descriptor, T resource) {
                            pool.release(descriptor, resource);
                        }
                    });
                    if (acquisitions[0] == 0) throw new AssertionError("Blur did not allocate targets");
                    probeScope(blur, target, GpuFormat.RGBA8_UNORM);
                    device.createCommandEncoder().copyTextureToBuffer(target.getColorTexture(), buffer, 0, () -> {
                        Throwable failure = null;
                        try (var mapped = buffer.map(true, false)) {
                            var pixels = mapped.data().order(ByteOrder.nativeOrder());
                            float[] expected = enabled
                                    ? new float[] {1.0f / 1024, 3.0f / 1024, 5.0f / 1024, 1}
                                    : new float[] {0, 1.0f / 255, 1.0f / 255, 1};
                            for (int channel = 0; channel < 4; channel++) {
                                float actual = format == GpuFormat.RGBA16_FLOAT
                                        ? Float.float16ToFloat(pixels.getShort(channel * 2))
                                        : pixels.getFloat(channel * 4);
                                if (!Float.isFinite(actual) || Math.abs(actual - expected[channel]) > 0.00001f) {
                                    throw new AssertionError("Blur channel " + channel + " lost float precision: "
                                            + actual + ", expected " + expected[channel]);
                                }
                            }
                        } catch (Throwable problem) {
                            failure = problem;
                        } finally {
                            try {
                                cleanup.run();
                            } catch (Throwable problem) {
                                if (failure == null) failure = problem;
                                else failure.addSuppressed(problem);
                            }
                        }
                        if (failure == null) result.complete(null);
                        else result.completeExceptionally(failure);
                    }, 0);
                } catch (Throwable failure) {
                    cleanup.run();
                    result.completeExceptionally(failure);
                }
            } catch (Throwable failure) {
                closeTarget.run();
                result.completeExceptionally(failure);
            }
        });
        context.waitFor(client -> result.isDone(), 200);
        result.join();
    }
}
