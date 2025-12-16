package com.qb20nh.cbbg.render.stbn;

import com.mojang.blaze3d.platform.NativeImage;
import com.qb20nh.cbbg.CbbgClient;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import static java.util.Objects.requireNonNull;

public class STBNLoader {

    private STBNLoader() {}

    private static final Path CACHE_DIR = STBNCache.CACHE_DIR;
    private static final String HASH_FILE_FMT = STBNCache.HASH_FILE_FMT;
    private static final String IMAGE_BASE_FMT = STBNCache.IMAGE_BASE_FMT;

    public static NativeImage[] loadOrGenerate(int width, int height, int frames,
            STBNGenerator.STBNFields fields) {
        // 1. Try Cache
        NativeImage[] cached = loadFromCache(width, height, frames);
        if (cached.length == frames) {
            CbbgClient.LOGGER.info("STBN Frames loaded from cache.");
            return cached;
        }

        // 2. Generate from fields
        if (fields != null) {
            NativeImage[] generated = generateFramesFromFields(fields, width, height, frames);
            CbbgClient.LOGGER.info("STBN Images generated from math fields.");
            saveToCache(generated, width, height, frames);
            return generated;
        }

        return null;
    }

    private static NativeImage[] generateFramesFromFields(STBNGenerator.STBNFields fields,
            int width, int height, int frames) {
        NativeImage[] images = new NativeImage[frames];
        for (int z = 0; z < frames; z++) {
            images[z] = new NativeImage(width, height, false);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int idx = (z * height + y) * width + x;
                    double u = fields.uField()[idx];
                    double v = fields.vField()[idx];
                    images[z].setPixel(x, y, STBNGenerator.calculatePixelColor(u, v));
                }
            }
        }
        return images;
    }

    private static NativeImage[] loadFromCache(int w, int h, int d) {
        try {
            if (!Files.exists(CACHE_DIR)) {
                return new NativeImage[0];
            }

            Path hashFile = CACHE_DIR.resolve(String.format(HASH_FILE_FMT, w, h, d));
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
                String baseName = String.format(IMAGE_BASE_FMT, w, h, d, z);
                String expectedHash = hashes.get(baseName + ".png");

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
            return images;
        } catch (Exception e) {
            CbbgClient.LOGGER.warn("Failed to load STBN cache", e);
            return new NativeImage[0];
        }
    }

    private static NativeImage loadCachedFrame(int w, int h, int d, int z, String expectedHash)
            throws IOException, NoSuchAlgorithmException {
        String baseName = String.format(IMAGE_BASE_FMT, w, h, d, z);
        Path imageFile = CACHE_DIR.resolve(baseName + ".png");

        if (!Files.exists(imageFile)) {
            return null;
        }

        byte[] imageBytes = Files.readAllBytes(imageFile);
        String actualHash = STBNCache.calculateSHA256(imageBytes);

        if (!actualHash.equalsIgnoreCase(expectedHash)) {
            return null;
        }

        return NativeImage.read(new ByteArrayInputStream(imageBytes));
    }

    private static void saveToCache(NativeImage[] images, int w, int h, int d) {
        try {
            Files.createDirectories(CACHE_DIR);
            StringBuilder hashContent = new StringBuilder();

            for (int z = 0; z < d; z++) {
                if (images[z] != null) {
                    String baseName = String.format(IMAGE_BASE_FMT, w, h, d, z);
                    String fileName = baseName + ".png";
                    Path imageFile = requireNonNull(CACHE_DIR.resolve(fileName));

                    images[z].writeToFile(imageFile);

                    // Compute hash
                    byte[] imageBytes = Files.readAllBytes(imageFile);
                    String hash = STBNCache.calculateSHA256(imageBytes);

                    // Append to hash manifest format: hash filename
                    hashContent.append(hash).append("  ").append(fileName)
                            .append(System.lineSeparator());
                }
            }

            Path hashFile =
                    requireNonNull(CACHE_DIR.resolve(String.format(HASH_FILE_FMT, w, h, d)));
            Files.writeString(hashFile, hashContent.toString());

        } catch (IOException | NoSuchAlgorithmException e) {
            CbbgClient.LOGGER.warn("Failed to save STBN cache", e);
        }
    }

    private static void cleanupImages(NativeImage[] images, int count) {
        for (int i = 0; i < count; i++) {
            if (images[i] != null) {
                images[i].close();
            }
        }
    }

    public static boolean isCacheValid(int w, int h, int d) {
        return STBNCache.isCacheValid(w, h, d);
    }
}
