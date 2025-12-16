package com.qb20nh.cbbg.render.stbn;

import net.fabricmc.loader.api.FabricLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class STBNCache {
    private static final Logger LOGGER = LoggerFactory.getLogger("cbbg-cache");
    public static final Path CACHE_DIR = FabricLoader.getInstance().getGameDir().resolve(".cbbg");
    public static final String HASH_FILE_FMT = "stbn_%dx%dx%d.sha256";
    public static final String IMAGE_BASE_FMT = "stbn_%dx%dx%d_%d";

    private STBNCache() {}

    public static boolean isCacheValid(int w, int h, int d) {
        try {
            if (!Files.exists(CACHE_DIR)) {
                return false;
            }

            Path hashFile = CACHE_DIR.resolve(String.format(HASH_FILE_FMT, w, h, d));
            if (!Files.exists(hashFile)) {
                return false;
            }

            Map<String, String> hashes = new HashMap<>();
            List<String> lines = Files.readAllLines(hashFile);
            for (String line : lines) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 2) {
                    hashes.put(parts[1], parts[0]);
                }
            }

            for (int z = 0; z < d; z++) {
                String baseName = String.format(IMAGE_BASE_FMT, w, h, d, z);
                String expectedHash = hashes.get(baseName + ".png");

                if (expectedHash == null) {
                    return false;
                }

                if (!checkFileHash(w, h, d, z, expectedHash)) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            LOGGER.warn("Failed to check STBN cache validity", e);
            return false;
        }
    }

    private static boolean checkFileHash(int w, int h, int d, int z, String expectedHash) {
        try {
            String baseName = String.format(IMAGE_BASE_FMT, w, h, d, z);
            Path imageFile = CACHE_DIR.resolve(baseName + ".png");

            if (!Files.exists(imageFile)) {
                return false;
            }

            byte[] imageBytes = Files.readAllBytes(imageFile);
            String actualHash = calculateSHA256(imageBytes);

            return actualHash.equalsIgnoreCase(expectedHash);
        } catch (Exception e) {
            return false;
        }
    }

    public static String calculateSHA256(byte[] data) throws NoSuchAlgorithmException {
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
