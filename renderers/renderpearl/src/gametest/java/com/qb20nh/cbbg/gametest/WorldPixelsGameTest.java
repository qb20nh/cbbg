package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.reference.DitherReference;
import com.qb20nh.cbbg.render.DitherController;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Screenshot;

/** Real world pixels versus a CPU oracle; retained captures are not approved world goldens. */
public final class WorldPixelsGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        CbbgConfig original = CbbgConfig.get();
        boolean hidden = context.computeOnClient(client -> client.gui.hud.isHidden());
        try (var world = context.worldBuilder().create()) {
            var server = world.getServer();
            server.runCommand("gamemode spectator @a");
            server.runCommand("time set 6000");
            server.runCommand("weather clear");
            server.runCommand("fill -8 -64 5 8 -50 5 minecraft:white_concrete");
            server.runCommand("fill -8 -64 5 -3 -55 5 minecraft:red_concrete");
            server.runCommand("fill 2 -57 5 8 -50 5 minecraft:blue_concrete");
            server.runCommand("setblock -1 -56 5 minecraft:glowstone");
            server.runCommand("tp @a 0.5 -58 0.5 0 0");
            world.getConnection().waitForChunksRender();
            context.runOnClient(client -> {
                if (!client.gui.hud.isHidden()) client.gui.hud.toggle();
                CbbgConfig.setMode(CbbgConfig.Mode.ENABLED);
                CbbgConfig.setPixelFormat(CbbgConfig.PixelFormat.RGBA32F);
                CbbgConfig.setStrength(2);
            });
            context.waitFor(client -> DitherController.isReady()
                    && client.gui.overlay() == null
                    && client.gameRenderer.mainRenderTarget().getColorTexture().getFormat()
                            == GpuFormat.RGBA32_FLOAT, 600);
            context.waitTicks(20);
            capture(context, false);
            capture(context, true);
        } finally {
            context.runOnClient(client -> {
                if (client.gui.hud.isHidden() != hidden) client.gui.hud.toggle();
                CbbgConfig.setMode(original.mode());
                CbbgConfig.setPixelFormat(original.pixelFormat());
                CbbgConfig.setStrength(original.strength());
            });
        }
    }

    private static void capture(ClientGameTestContext context, boolean demo) {
        CompletableFuture<float[]> source = new CompletableFuture<>();
        CompletableFuture<int[]> actual = new CompletableFuture<>();
        CompletableFuture<Void> result = new CompletableFuture<>();
        context.runOnClient(client -> {
            try {
                CbbgConfig.setMode(demo ? CbbgConfig.Mode.DEMO : CbbgConfig.Mode.ENABLED);
                var frameField = DitherController.class.getDeclaredField("shownFrame");
                frameField.setAccessible(true);
                frameField.setInt(null, 0);
                var framesField = DitherController.class.getDeclaredField("frames");
                framesField.setAccessible(true);
                NativeImage noiseImage = ((NativeImage[]) framesField.get(null))[0];
                int[] noise = noiseImage.getPixels();
                int tileSize = noiseImage.getWidth();
                var main = client.gameRenderer.mainRenderTarget();
                int width = main.width;
                int height = main.height;
                Path directory = Path.of(System.getProperty("cbbg.test.evidence"),
                        "world-pixels", demo ? "demo" : "enabled");
                Files.createDirectories(directory);
                noiseImage.writeToFile(directory.resolve("noise.png"));
                var buffer = RenderSystem.getDevice().createBuffer(
                        () -> "CBBG world float readback", 9, width * height * 16);
                RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(
                        main.getColorTexture(), buffer, 0, () -> {
                            try (var mapped = buffer.map(true, false)) {
                                var bytes = mapped.data().order(ByteOrder.nativeOrder());
                                float[] values = new float[width * height * 4];
                                bytes.asFloatBuffer().get(values);
                                byte[] retained = new byte[values.length * 4];
                                bytes.get(retained);
                                Files.write(directory.resolve("source-rgba32f.bin"), retained);
                                source.complete(values);
                            } catch (Throwable failure) {
                                source.completeExceptionally(failure);
                            } finally {
                                buffer.close();
                            }
                        }, 0);
                Screenshot.takeScreenshot(main, image -> {
                    try (image) {
                        if (image.getWidth() != width || image.getHeight() != height) {
                            throw new AssertionError("World screenshot dimensions changed");
                        }
                        image.writeToFile(directory.resolve("actual.png"));
                        actual.complete(image.getPixels());
                    } catch (Throwable failure) {
                        actual.completeExceptionally(failure);
                    }
                });
                source.thenCombine(actual, (floats, pixels) -> {
                    compare(directory, width, height, tileSize, noise, floats, pixels, demo);
                    return null;
                }).whenComplete((unused, failure) -> {
                    if (failure == null) result.complete(null);
                    else result.completeExceptionally(failure);
                });
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        });
        context.waitFor(client -> result.isDone(), 200);
        result.join();
    }

    private static void compare(Path directory, int width, int height, int tileSize,
            int[] noise, float[] source, int[] actual, boolean demo) {
        int changed = 0;
        int boundaries = 0;
        int mismatches = 0;
        String first = null;
        try (NativeImage expectedImage = new NativeImage(width, height, false)) {
            for (int y = 0; y < height; y++) {
                int gpuY = height - 1 - y;
                for (int x = 0; x < width; x++) {
                    int noisePixel = noise[DitherReference.noiseCoordinate(gpuY, 1, tileSize)
                            * tileSize + DitherReference.noiseCoordinate(x, 1, tileSize)];
                    int expected = 0xff000000;
                    for (int channel = 0; channel < 3; channel++) {
                        int shift = 16 - channel * 8;
                        int noiseByte = noisePixel >>> shift & 255;
                        float input = source[(gpuY * width + x) * 4 + channel];
                        if (!Float.isFinite(input)) throw new AssertionError("Nonfinite world source");
                        int value = DitherReference.channel(input, noiseByte, 2, x, width, demo);
                        expected |= value << shift;
                        int plain = DitherReference.channel(input, noiseByte, 0, x, width, demo);
                        if (value != plain) changed++;
                        // Only shader float arithmetic within 0.0001 of an integer rounding
                        // threshold may differ by one LSB; all other channels must be exact.
                        double rounded = Math.clamp(input, 0, 1) * 255.0
                                + (noiseByte / 255.0 - 0.5) * (demo && x < width / 2 ? 0 : 2) + 0.5;
                        boolean boundary = Math.abs(rounded - Math.rint(rounded)) <= 0.0001;
                        if (boundary) boundaries++;
                        int observed = actual[y * width + x] >>> shift & 255;
                        if (Math.abs(value - observed) > (boundary ? 1 : 0)) {
                            mismatches++;
                            if (first == null) first = x + "," + y + ":" + channel
                                    + " expected=" + value + " actual=" + observed;
                        }
                    }
                    if ((actual[y * width + x] >>> 24) != 255) {
                        throw new AssertionError("World screenshot alpha was not opaque");
                    }
                    expectedImage.setPixel(x, y, expected);
                }
            }
            expectedImage.writeToFile(directory.resolve("expected.png"));
            Files.writeString(directory.resolve("comparison.json"), "{\"width\":" + width
                    + ",\"height\":" + height + ",\"noiseFrame\":0,\"strength\":2"
                    + ",\"sourceByteOrder\":\"" + ByteOrder.nativeOrder() + "\""
                    + ",\"changedChannels\":" + changed + ",\"boundaryChannels\":" + boundaries
                    + ",\"mismatchedChannels\":" + mismatches + ",\"approvedGolden\":false}\n");
            if (changed == 0) throw new AssertionError("World fixture cannot detect a missing effect");
            if (mismatches != 0) throw new AssertionError("World CPU pixel mismatch: " + first
                    + " (" + mismatches + " channels)");
        } catch (java.io.IOException failure) {
            throw new AssertionError("Could not retain world pixel evidence", failure);
        }
    }
}
