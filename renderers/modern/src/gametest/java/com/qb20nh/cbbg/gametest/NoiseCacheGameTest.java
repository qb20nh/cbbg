package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.platform.NativeImage;
import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.math.BlueNoise;
import com.qb20nh.cbbg.render.DitherController;
import com.qb20nh.cbbg.render.stbn.STBNCache;
import com.qb20nh.cbbg.render.stbn.STBNGenerator;
import com.qb20nh.cbbg.render.stbn.STBNLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/** Exercises the asynchronous generator and Minecraft PNG codec in an isolated game directory. */
public final class NoiseCacheGameTest implements FabricClientGameTest {
    private static final int SIZE = 16;
    private static final int DEPTH = 4;

    @Override
    public void runTest(ClientGameTestContext context) {
        CbbgConfig original = CbbgConfig.get();
        context.waitFor(client -> DitherController.isReady(), 600);
        context.runOnClient(client -> CbbgConfig.setMode(CbbgConfig.Mode.DISABLED));
        context.waitTicks(3);
        try {
            // These dimensions differ from the renderer test's 16x16x8 cache.
            for (int frame = -1; frame < DEPTH; frame++) {
                if (Files.exists(path(frame))) {
                    throw new AssertionError("Cache test requires an unused fixture: " + path(frame));
                }
            }
            for (long seed : new long[] {0L, 74123L}) {
                context.runOnClient(client -> CbbgConfig.setStbnSeed(seed));
                if (STBNCache.isCacheValid(SIZE, SIZE, DEPTH, seed)) {
                    throw new AssertionError("Cold or different-seed cache was accepted");
                }
                int[] cold = generateAndRead(seed, false);
                byte[] manifest = Files.readAllBytes(path(-1));
                var modified = Files.getLastModifiedTime(path(0));
                assertPixels(cold, generateAndRead(seed, true), "warm cache");
                if (!Arrays.equals(manifest, Files.readAllBytes(path(-1)))
                        || !modified.equals(Files.getLastModifiedTime(path(0)))) {
                    throw new AssertionError("Warm cache was rewritten");
                }

                // Corrupt a later frame, so the loader also encounters already decoded images.
                Files.write(path(DEPTH - 1), new byte[] {1, 2, 3});
                if (STBNCache.isCacheValid(SIZE, SIZE, DEPTH, seed)) {
                    throw new AssertionError("Corrupt image was accepted");
                }
                assertPixels(cold, generateAndRead(seed, false), "corrupt image recovery");
                Files.delete(path(DEPTH - 1));
                assertPixels(cold, generateAndRead(seed, false), "missing image recovery");
                Files.writeString(path(-1), "invalid manifest\n");
                assertPixels(cold, generateAndRead(seed, false), "corrupt manifest recovery");
            }
        } catch (Exception failure) {
            throw new AssertionError("Noise cache lifecycle failed", failure);
        } finally {
            context.runOnClient(client -> {
                CbbgConfig.setStbnSeed(original.stbnSeed());
                CbbgConfig.setMode(original.mode());
            });
        }
        context.waitFor(client -> DitherController.isReady(), 600);
    }

    private static int[] generateAndRead(long seed, boolean warm) throws Exception {
        var fields = STBNGenerator.generateAsync(SIZE, SIZE, DEPTH, seed).get(30, TimeUnit.SECONDS);
        if ((fields == null) != warm) {
            throw new AssertionError("Expected " + (warm ? "cached" : "generated") + " noise fields");
        }
        NativeImage[] images = STBNLoader.loadOrGenerate(SIZE, SIZE, DEPTH, fields);
        if (images == null) {
            throw new AssertionError("Noise loader produced no images");
        }
        try {
            if (images.length != DEPTH || !STBNCache.isCacheValid(SIZE, SIZE, DEPTH, seed)) {
                throw new AssertionError("Generated cache is incomplete");
            }
            double[] u = BlueNoise.generateScalarField(SIZE, SIZE, DEPTH, BlueNoise.stbnUSeed(seed));
            double[] v = BlueNoise.generateScalarField(SIZE, SIZE, DEPTH, BlueNoise.stbnVSeed(seed));
            int[] pixels = new int[SIZE * SIZE * DEPTH];
            for (int z = 0; z < DEPTH; z++) {
                if (images[z].getWidth() != SIZE || images[z].getHeight() != SIZE) {
                    throw new AssertionError("Cached image has wrong dimensions");
                }
                for (int y = 0; y < SIZE; y++) {
                    for (int x = 0; x < SIZE; x++) {
                        int index = (z * SIZE + y) * SIZE + x;
                        pixels[index] = images[z].getPixel(x, y);
                        if (pixels[index] != BlueNoise.calculatePixelColor(u[index], v[index])) {
                            throw new AssertionError("Decoded pixel differs from CPU reference at " + index);
                        }
                    }
                }
            }
            return pixels;
        } finally {
            for (NativeImage image : images) {
                if (image != null) image.close();
            }
        }
    }

    private static void assertPixels(int[] expected, int[] actual, String stage) {
        if (!Arrays.equals(expected, actual)) {
            throw new AssertionError("Decoded noise changed during " + stage);
        }
    }

    private static Path path(int frame) {
        String name = frame < 0 ? String.format(STBNCache.HASH_FILE_FMT, SIZE, SIZE, DEPTH)
                : String.format(STBNCache.IMAGE_BASE_FMT, SIZE, SIZE, DEPTH, frame) + ".png";
        return STBNCache.CACHE_DIR.resolve(name);
    }
}
