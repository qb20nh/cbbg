package com.qb20nh.cbbg.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.qb20nh.cbbg.CbbgClient;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import static java.util.Objects.requireNonNull;
import net.fabricmc.loader.api.FabricLoader;

public class STBNLoader {

    private STBNLoader() {}

    private static final Path CACHE_DIR = FabricLoader.getInstance().getGameDir().resolve(".cbbg");

    public static NativeImage[] loadOrGenerate(int width, int height, int frames,
            STBNGenerator.STBNFields fields) {
        // 1. Try Cache
        NativeImage[] cached = loadFromCache(width, height, frames);
        if (cached != null && cached.length == frames) {
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
                return null;
            }

            Path hashFile = CACHE_DIR.resolve(String.format("stbn_%dx%dx%d.sha256", w, h, d));
            if (!Files.exists(hashFile)) {
                return null;
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
                    return null;
                }

                images[z] = loadCachedFrame(w, h, d, z, expectedHash);
                if (images[z] == null) {
                    cleanupImages(images, z);
                    return null;
                }
            }
            return images;
        } catch (Exception e) {
            CbbgClient.LOGGER.warn("Failed to load STBN cache", e);
            return null;
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
                    String baseName = String.format("stbn_%dx%dx%d_%d", w, h, d, z);
                    String fileName = baseName + ".png";
                    Path imageFile = requireNonNull(CACHE_DIR.resolve(fileName));

                    images[z].writeToFile(imageFile);

                    // Compute hash
                    byte[] imageBytes = Files.readAllBytes(imageFile);
                    String hash = calculateSHA256(imageBytes);

                    // Append to hash manifest format: hash filename
                    hashContent.append(hash).append("  ").append(fileName)
                            .append(System.lineSeparator());
                }
            }

            Path hashFile = requireNonNull(
                    CACHE_DIR.resolve(String.format("stbn_%dx%dx%d.sha256", w, h, d)));
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
