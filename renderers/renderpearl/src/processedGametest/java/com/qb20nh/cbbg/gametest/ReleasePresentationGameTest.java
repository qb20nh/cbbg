package com.qb20nh.cbbg.gametest;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.qb20nh.cbbg.reference.DitherReference;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.renderer.RenderPipelines;
import org.joml.Vector4f;

/** Checks live presentation and screenshot noise from the processed, packaged mod. */
public final class ReleasePresentationGameTest implements FabricClientGameTest {
    private static final int SIZE = 16;
    private static final int DEPTH = 8;
    private static final long SEED = 0;
    private static final int WAIT_TICKS = 600;
    private static final Pattern FRAME = Pattern.compile("\\bstbn=(\\d+)/(\\d+)\\b");
    private static final Vector4f SOURCE = new Vector4f(127.25f / 255, 127.25f / 255,
            127.25f / 255, 0.375f);

    @Override
    public void runTest(ClientGameTestContext context) {
        JsonObject previous = ReleaseClient.settings();
        if (previous.get("strength").getAsFloat() != 2.0f) {
            throw new AssertionError("Presentation reference requires startup CBBG strength 2");
        }
        try (var world = context.worldBuilder().create()) {
            try {
                world.getConnection().waitForChunksRender();
                ReleaseClient.command(context, "mode set disabled");
                ReleaseClient.command(context, "stbn size " + SIZE);
                ReleaseClient.command(context, "stbn depth " + DEPTH);
                ReleaseClient.command(context, "stbn seed " + SEED);
                ReleaseClient.command(context, "format set rgba32f");
                ReleaseClient.command(context, "stbn generate");
                ReleaseClient.awaitCache(context, SIZE, DEPTH, SEED);
                retainCache();
                ReleaseClient.command(context, "mode set enabled");
                ReleaseClient.awaitFormat(context, GpuFormat.RGBA32_FLOAT);
                context.waitFor(client -> ready(client), WAIT_TICKS);

                long before = ProcessedRenderObservations.draws();
                ReleaseClient.awaitDrawAfter(context, before);
                Sample first = sample(context, "presentation-gpu-noise.png");
                assertNoise(first);
                Set<Integer> seen = new HashSet<>();
                seen.add(first.frame());
                Sample last = first;
                long elapsedDraws = 0;
                for (int attempt = 0; attempt < 24 && (elapsedDraws < DEPTH || seen.size() < 2); attempt++) {
                    long count = last.draws();
                    ReleaseClient.awaitDrawAfter(context, count);
                    Sample next = sample(context, null);
                    assertNoise(next);
                    long delta = next.draws() - last.draws();
                    if (delta <= 0 || next.frame() != (last.frame() + delta) % DEPTH) {
                        throw new AssertionError("Normal presentation did not rotate STBN by its draw count: "
                                + last.frame() + " -> " + next.frame() + " over " + delta + " draws");
                    }
                    elapsedDraws += delta;
                    seen.add(next.frame());
                    last = next;
                }
                if (elapsedDraws < DEPTH || seen.size() < 2) {
                    throw new AssertionError("Did not observe distinct noise frames across a full temporal cycle");
                }

                screenshotPair(context, false);
                GpuTextureView active = context.computeOnClient(client -> {
                    GpuTextureView noise = ProcessedRenderObservations.lastDitherNoise();
                    if (noise == null || noise.texture().isClosed()) {
                        throw new AssertionError("Live noise was not bound before disable");
                    }
                    client.getConnection().sendCommand("cbbg mode set disabled");
                    return noise;
                });
                ReleaseClient.awaitFormat(context, GpuFormat.RGBA8_UNORM);
                context.waitFor(client -> active.texture().isClosed(), WAIT_TICKS);
                ReleaseClient.assertNoDraws(context);

                long stopped = ProcessedRenderObservations.draws();
                ReleaseClient.command(context, "mode set demo");
                ReleaseClient.awaitFormat(context, GpuFormat.RGBA32_FLOAT);
                ReleaseClient.awaitDrawAfter(context, stopped);
                context.waitFor(client -> ready(client), WAIT_TICKS);
                screenshotPair(context, true);
            } finally {
                restore(context, previous);
            }
        }
    }

    private static boolean ready(Minecraft client) {
        GpuTextureView noise = ProcessedRenderObservations.lastDitherNoise();
        return client.gui.overlay() == null && noise != null && !noise.texture().isClosed()
                && noise.getWidth(0) == SIZE && noise.getHeight(0) == SIZE
                && debugFrame(client) >= 0;
    }

    private static int debugFrame(Minecraft client) {
        for (String line : ReleaseDebugState.read(client)) {
            var match = FRAME.matcher(line);
            if (match.find()) {
                int frame = Integer.parseInt(match.group(1));
                int depth = Integer.parseInt(match.group(2));
                if (depth != DEPTH || frame < 0 || frame >= depth) {
                    throw new AssertionError("Unexpected packaged STBN state: " + line);
                }
                return frame;
            }
        }
        return -1;
    }

    private static Sample sample(ClientGameTestContext context, String evidenceName) {
        Sample sample = context.computeOnClient(client -> {
            int frame = debugFrame(client);
            GpuTextureView noise = ProcessedRenderObservations.lastDitherNoise();
            if (frame < 0 || noise == null || noise.texture().isClosed()) {
                throw new AssertionError("No live bound noise for presentation sample");
            }
            long draws = ProcessedRenderObservations.draws();
            CompletableFuture<int[]> pixels = readNoise(noise, evidenceName);
            if (ProcessedRenderObservations.draws() != draws || debugFrame(client) != frame) {
                throw new AssertionError("Noise probe advanced the presentation sequence");
            }
            return new Sample(draws, frame, pixels);
        });
        context.waitFor(client -> sample.pixels().isDone(), 200);
        sample.pixels().join();
        return sample;
    }

    private static CompletableFuture<int[]> readNoise(GpuTextureView noise, String evidenceName) {
        CompletableFuture<int[]> pixels = new CompletableFuture<>();
        TextureTarget target = new TextureTarget("CBBG live noise probe", SIZE, SIZE,
                GpuFormat.RGBA8_UNORM, null);
        try {
            try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
                    .createRenderPass(() -> "CBBG live noise probe", target.getColorTextureView(),
                            Optional.empty())) {
                pass.setPipeline(RenderSystem.getCompiledPipeline(RenderPipelines.TRACY_BLIT));
                RenderSystem.bindDefaultUniforms(pass);
                pass.setUniform("InSampler", noise,
                        RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
                pass.draw(3, 1, 0, 0);
            }
            Screenshot.takeScreenshot(target, 1, image -> {
                try (image) {
                    if (image.getWidth() != SIZE || image.getHeight() != SIZE) {
                        throw new AssertionError("Live noise readback has wrong dimensions");
                    }
                    if (evidenceName != null) {
                        image.writeToFile(evidence().resolve(evidenceName));
                    }
                    pixels.complete(image.getPixels());
                } catch (Throwable failure) {
                    pixels.completeExceptionally(failure);
                } finally {
                    target.destroyBuffers();
                }
            });
        } catch (Throwable failure) {
            target.destroyBuffers();
            pixels.completeExceptionally(failure);
        }
        return pixels;
    }

    private static void assertNoise(Sample sample) {
        Path framePath = ReleaseClient.cache().resolve(frameName(sample.frame()));
        try (var input = Files.newInputStream(framePath);
                NativeImage disk = NativeImage.read(input)) {
            int[] gpu = sample.pixels().join();
            for (int y = 0; y < SIZE; y++) {
                for (int x = 0; x < SIZE; x++) {
                    // NativeImage cache row zero is uploaded to the GPU's bottom row;
                    // vanilla screenshots return their first row from the top.
                    int expected = disk.getPixel(x, SIZE - 1 - y);
                    int actual = gpu[y * SIZE + x];
                    if (actual != expected) {
                        throw new AssertionError("Bound GPU noise differs from cache frame "
                                + sample.frame() + " at " + x + "," + y + ": expected "
                                + Integer.toHexString(expected) + ", got "
                                + Integer.toHexString(actual));
                    }
                }
            }
        } catch (IOException failure) {
            throw new AssertionError("Could not decode generated noise frame", failure);
        }
    }

    private static void screenshotPair(ClientGameTestContext context, boolean demo) {
        String prefix = "presentation-" + System.getProperty("cbbg.test.backend")
                + (demo ? "-demo" : "-enabled");
        var pair = context.computeOnClient(client -> {
            var main = client.gameRenderer.mainRenderTarget();
            int frame = debugFrame(client);
            long draws = ProcessedRenderObservations.draws();
            RenderSystem.getDevice().createCommandEncoder().clearColorTexture(main.getColorTexture(), SOURCE);
            CompletableFuture<int[]> first = screenshot(main, prefix + "-1.png");
            CompletableFuture<int[]> second = screenshot(main, prefix + "-2.png");
            if (debugFrame(client) != frame || ProcessedRenderObservations.draws() != draws + 2) {
                throw new AssertionError("Vanilla screenshots advanced the temporal sequence");
            }
            GpuTextureView noise = ProcessedRenderObservations.lastDitherNoise();
            if (noise == null || noise.texture().isClosed()) {
                throw new AssertionError("Vanilla screenshot did not bind live noise");
            }
            CompletableFuture<int[]> noisePixels = readNoise(noise, null);
            if (debugFrame(client) != frame || ProcessedRenderObservations.draws() != draws + 2) {
                throw new AssertionError("Noise readback advanced the temporal sequence");
            }
            return new Pair(main.width, main.height, frame, first, second, noisePixels);
        });
        context.waitFor(client -> pair.first().isDone() && pair.second().isDone()
                && pair.noise().isDone(), 200);
        int[] first = pair.first().join();
        int[] second = pair.second().join();
        int[] noise = pair.noise().join();
        assertNoise(new Sample(0, pair.frame(), CompletableFuture.completedFuture(noise)));
        if (!Arrays.equals(first, second)) {
            throw new AssertionError("Consecutive vanilla screenshots used different noise");
        }
        int changed = 0;
        for (int y = 0; y < pair.height(); y++) {
            int gpuY = pair.height() - 1 - y;
            for (int x = 0; x < pair.width(); x++) {
                int noiseX = DitherReference.noiseCoordinate(x, 1, SIZE);
                int noiseY = DitherReference.noiseCoordinate(gpuY, 1, SIZE);
                int noisePixel = noise[(SIZE - 1 - noiseY) * SIZE + noiseX];
                int expected = 0xff000000;
                for (int channel = 0; channel < 3; channel++) {
                    int shift = channel * 8;
                    int value = DitherReference.channel(127.25 / 255,
                            noisePixel >>> shift & 255, 2, x, pair.width(), demo);
                    expected |= value << shift;
                }
                int actual = first[y * pair.width() + x];
                if (actual != expected) {
                    throw new AssertionError("Live screenshot mismatch at " + x + "," + y
                            + " frame=" + pair.frame() + " demo=" + demo + " expected="
                            + Integer.toHexString(expected) + " actual=" + Integer.toHexString(actual));
                }
                if (actual != 0xff7f7f7f) changed++;
            }
        }
        if (changed == 0) throw new AssertionError("Live screenshot omitted the dither effect");
    }

    private static CompletableFuture<int[]> screenshot(com.mojang.blaze3d.pipeline.RenderTarget target,
            String name) {
        CompletableFuture<int[]> pixels = new CompletableFuture<>();
        Screenshot.takeScreenshot(target, 1, image -> {
            try (image) {
                if (image.getWidth() != target.width || image.getHeight() != target.height) {
                    throw new AssertionError("Live screenshot has wrong dimensions");
                }
                image.writeToFile(evidence().resolve(name));
                pixels.complete(image.getPixels());
            } catch (Throwable failure) {
                pixels.completeExceptionally(failure);
            }
        });
        return pixels;
    }

    private static void retainCache() {
        try {
            Path evidence = evidence().resolve("noise-" + System.getProperty("cbbg.test.backend"));
            Files.createDirectories(evidence);
            for (int frame = 0; frame < DEPTH; frame++) {
                String name = frameName(frame);
                Files.copy(ReleaseClient.cache().resolve(name), evidence.resolve(name),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            Path manifest = ReleaseClient.manifest(SIZE, DEPTH);
            Files.copy(manifest, evidence.resolve(manifest.getFileName()),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) {
            throw new AssertionError("Could not retain generated noise evidence", failure);
        }
    }

    private static Path evidence() {
        String location = System.getProperty("cbbg.test.evidence");
        if (location == null || location.isBlank()) throw new AssertionError("cbbg.test.evidence is required");
        Path directory = Path.of(location);
        try {
            Files.createDirectories(directory);
        } catch (IOException failure) {
            throw new AssertionError("Could not create presentation evidence directory", failure);
        }
        return directory;
    }

    private static String frameName(int frame) {
        return "stbn_" + SIZE + "x" + SIZE + "x" + DEPTH + "_" + frame + ".png";
    }

    private static void restore(ClientGameTestContext context, JsonObject previous) {
        ReleaseClient.command(context, "mode set disabled");
        ReleaseClient.command(context, "stbn size " + previous.get("stbnSize").getAsInt());
        ReleaseClient.command(context, "stbn depth " + previous.get("stbnDepth").getAsInt());
        ReleaseClient.command(context, "stbn seed " + previous.get("stbnSeed").getAsLong());
        ReleaseClient.command(context, "format set "
                + previous.get("pixelFormat").getAsString().toLowerCase(java.util.Locale.ROOT));
        ReleaseClient.command(context, "mode set "
                + previous.get("mode").getAsString().toLowerCase(java.util.Locale.ROOT));
        context.waitFor(client -> ReleaseClient.settings().equals(previous), WAIT_TICKS);
    }

    private record Sample(long draws, int frame, CompletableFuture<int[]> pixels) {}
    private record Pair(int width, int height, int frame, CompletableFuture<int[]> first,
            CompletableFuture<int[]> second, CompletableFuture<int[]> noise) {}
}
