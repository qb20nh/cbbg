package com.qb20nh.cbbg.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.qb20nh.cbbg.math.MiniFFT;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import static java.util.Objects.requireNonNull;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class STBNGenerator {

    private STBNGenerator() {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger("cbbg-gen");
    private static final Path CACHE_DIR = FabricLoader.getInstance().getGameDir().resolve(".cbbg");

    public static CompletableFuture<NativeImage[]> generateAsync(int width, int height, int frames) {
        return CompletableFuture.supplyAsync(() -> {
            long start = System.currentTimeMillis();
            LOGGER.info("Starting Async STBN Generation ({}x{}x{})...", width, height, frames);

            // Try loading from cache first
            NativeImage[] cached = loadFromCache(width, height, frames);
            if (cached != null && cached.length > 0) {
                return cached;
            }

            NativeImage[] images = new NativeImage[frames];
            try {
                // Generate U and V fields (Spatio-Temporal Blue Noise)
                double[] uField = generateScalarField(width, height, frames, 1234);
                double[] vField = generateScalarField(width, height, frames, 5678);

                // Map to Unit Vectors and create images

                for (int z = 0; z < frames; z++) {
                    images[z] = generateFrame(width, height, z, uField, vField);
                }

                long dt = System.currentTimeMillis() - start;
                LOGGER.info("STBN Generation Complete in {} ms", dt);

                // Save to cache
                saveToCache(images, width, height, frames);

                return images;

            } catch (Exception e) {
                LOGGER.error("STBN Generation Failed", e);
                // Free any allocated images on failure
                for (NativeImage img : images) {
                    if (img != null)
                        img.close();
                }
                return null; // Handle null in caller
            }
        });
    }

    private static double[] generateScalarField(int w, int h, int d, long seed) {
        int size = w * h * d;
        double[] real = initializeRandomField(size, seed);
        double[] imag = new double[size];

        double[] distSq = computeDistanceGrid(w, h, d);
        double[] filter = computeFilterWeights(distSq);

        Integer[] indices = new Integer[size];
        for (int i = 0; i < size; i++) {
            indices[i] = i;
        }

        int iterations = 10;
        for (int iter = 0; iter < iterations; iter++) {
            MiniFFT.fft3d(real, imag, w, h, d, false);
            applyFilter(real, imag, filter);
            MiniFFT.fft3d(real, imag, w, h, d, true);
            applyHistogramMatching(real, imag, indices);
        }

        return real;
    }

    private static double[] initializeRandomField(int size, long seed) {
        double[] real = new double[size];
        Random rng = new Random(seed);
        for (int i = 0; i < size; i++) {
            real[i] = rng.nextDouble();
        }
        return real;
    }

    private static double[] computeDistanceGrid(int w, int h, int d) {
        int size = w * h * d;
        double[] distSq = new double[size];

        for (int z = 0; z < d; z++) {
            double kz = (z <= d / 2.0) ? (double) z / d : (double) (z - d) / d;
            for (int y = 0; y < h; y++) {
                double ky = (y <= h / 2.0) ? (double) y / h : (double) (y - h) / h;
                for (int x = 0; x < w; x++) {
                    double kx = (x <= w / 2.0) ? (double) x / w : (double) (x - w) / w;
                    int idx = (z * h + y) * w + x;
                    distSq[idx] = kx * kx + ky * ky + kz * kz;
                }
            }
        }
        return distSq;
    }

    private static double[] computeFilterWeights(double[] distSq) {
        int size = distSq.length;
        double[] filter = new double[size];
        double sigma = 0.2; // Aggressive Filter
        double twoSigmaSq = 2 * sigma * sigma;

        for (int i = 0; i < size; i++) {
            filter[i] = 1.0 - Math.exp(-distSq[i] / twoSigmaSq);
        }
        return filter;
    }

    private static void applyFilter(double[] real, double[] imag, double[] filter) {
        int size = real.length;
        for (int i = 0; i < size; i++) {
            double mag = Math.sqrt(real[i] * real[i] + imag[i] * imag[i]);
            double newMag = mag * filter[i];

            if (mag > 1e-9) {
                real[i] *= (newMag / mag);
                imag[i] *= (newMag / mag);
            } else {
                real[i] = 0;
                imag[i] = 0;
            }
        }
    }

    private static void applyHistogramMatching(double[] real, double[] imag, Integer[] indices) {
        int size = real.length;
        Arrays.sort(indices, (a, b) -> Double.compare(real[a], real[b]));

        for (int k = 0; k < size; k++) {
            int idx = indices[k];
            real[idx] = (double) k / (size - 1);
            imag[idx] = 0;
        }
    }

    private static NativeImage generateFrame(int width, int height, int z, double[] uField, double[] vField) {
        NativeImage img = new NativeImage(width, height, false);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int idx = (z * height + y) * width + x;
                double u = uField[idx];
                double v = vField[idx];
                img.setPixel(x, y, calculatePixelColor(u, v));
            }
        }
        return img;
    }

    private static int calculatePixelColor(double u, double v) {
        // Spherical Mapping
        double zz = 2.0 * u - 1.0;
        double theta = 2.0 * Math.PI * v;

        // Clamp z for safety
        if (zz > 1.0)
            zz = 1.0;
        if (zz < -1.0)
            zz = -1.0;

        double radius = Math.sqrt(1.0 - zz * zz);
        double xx = radius * Math.cos(theta);
        double yy = radius * Math.sin(theta);

        // Quantize to 0..255
        int r = (int) Math.round((xx + 1.0) * 127.5);
        int g = (int) Math.round((yy + 1.0) * 127.5);
        int b = (int) Math.round((zz + 1.0) * 127.5);

        // Clamp
        r = Math.clamp(r, 0, 255);
        g = Math.clamp(g, 0, 255);
        b = Math.clamp(b, 0, 255);

        // Clean Alpha (255)
        return (0xFF << 24) | (b << 16) | (g << 8) | r;
    }

    private static NativeImage[] loadFromCache(int w, int h, int d) {
        try {
            if (!Files.exists(CACHE_DIR)) {
                return new NativeImage[0];
            }

            Path hashFile = CACHE_DIR.resolve(String.format("stbn_%dx%dx%d.sha256", w, h, d));
            if (!Files.exists(hashFile)) {
                return new NativeImage[0];
            }

            Map<String, String> hashes = new HashMap<>();
            List<String> lines = Files.readAllLines(hashFile);
            for (String line : lines) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 2) {
                    hashes.put(parts[1], parts[0]);
                }
            }

            NativeImage[] images = new NativeImage[d];
            for (int z = 0; z < d; z++) {
                String baseName = String.format("stbn_%dx%dx%d_%d.png", w, h, d, z);
                String expectedHash = hashes.get(baseName);

                if (expectedHash == null) {
                    cleanupImages(images, z);
                    return new NativeImage[0];
                }

                images[z] = loadCachedFrame(w, h, d, z, expectedHash);
                if (images[z] == null) {
                    cleanupImages(images, z);
                    return new NativeImage[0];
                }
            }
            LOGGER.info("STBN Cache Loaded (Integrity Verified)");
            return images;
        } catch (Exception e) {
            LOGGER.warn("Failed to load STBN cache", e);
            return new NativeImage[0];
        }
    }

    private static NativeImage loadCachedFrame(int w, int h, int d, int z, String expectedHash)
            throws IOException, NoSuchAlgorithmException {
        String baseName = String.format("stbn_%dx%dx%d_%d", w, h, d, z);
        Path imageFile = CACHE_DIR.resolve(baseName + ".png");

        if (!Files.exists(imageFile)) {
            return null;
        }

        byte[] imageBytes = Files.readAllBytes(imageFile);
        String actualHash = calculateSHA256(imageBytes);

        if (!actualHash.equalsIgnoreCase(expectedHash)) {
            LOGGER.warn("STBN cache integrity check failed for frame {}. Expected: {}, Actual: {}", z,
                    expectedHash, actualHash);
            return null;
        }

        return NativeImage.read(new ByteArrayInputStream(imageBytes));
    }

    private static void cleanupImages(NativeImage[] images, int count) {
        for (int i = 0; i < count; i++) {
            if (images[i] != null) {
                images[i].close();
            }
        }
    }

    private static void saveToCache(NativeImage[] images, int w, int h, int d) {
        try {
            Files.createDirectories(CACHE_DIR);
            StringBuilder hashContent = new StringBuilder();

            for (int z = 0; z < d; z++) {
                if (images[z] != null) {
                    String baseName = String.format("stbn_%dx%dx%d_%d", w, h, d, z);
                    String fileName = baseName + ".png";
                    Path imageFile = requireNonNull(CACHE_DIR.resolve(fileName));

                    images[z].writeToFile(imageFile);

                    // Compute hash
                    byte[] imageBytes = Files.readAllBytes(imageFile);
                    String hash = calculateSHA256(imageBytes);

                    // Append to hash manifest format: hash filename
                    hashContent.append(hash).append("  ").append(fileName).append(System.lineSeparator());
                }
            }

            Path hashFile = requireNonNull(CACHE_DIR.resolve(String.format("stbn_%dx%dx%d.sha256", w, h, d)));
            Files.writeString(hashFile, hashContent.toString());

            LOGGER.info("STBN Cache Saved to {}", CACHE_DIR);
        } catch (IOException | NoSuchAlgorithmException e) {
            LOGGER.warn("Failed to save STBN cache", e);
        }
    }

    private static String calculateSHA256(byte[] data) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(data);
        StringBuilder hexString = new StringBuilder();
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1)
                hexString.append('0');
            hexString.append(hex);
        }
        return hexString.toString();
    }
}
