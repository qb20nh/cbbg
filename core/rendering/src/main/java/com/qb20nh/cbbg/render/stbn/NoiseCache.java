package com.qb20nh.cbbg.render.stbn;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;

public final class NoiseCache {
  private final Path directory;
  private final BiConsumer<String, Throwable> warning;
  public static final String HASH_FILE_FMT = "stbn_%dx%dx%d.sha256";
  public static final String IMAGE_BASE_FMT = "stbn_%dx%dx%d_%d";

  public NoiseCache(Path directory, BiConsumer<String, Throwable> warning) {
    this.directory = Objects.requireNonNull(directory, "directory");
    this.warning = Objects.requireNonNull(warning, "warning");
  }

  public boolean isCacheValid(int w, int h, int d) {
    return validateCache(w, h, d, null);
  }

  public boolean isCacheValid(int w, int h, int d, long seed) {
    return validateCache(w, h, d, seed);
  }

  private boolean validateCache(int w, int h, int d, Long seed) {
    try {
      if (!Files.exists(directory)) {
        return false;
      }

      Path hashFile = directory.resolve(String.format(HASH_FILE_FMT, w, h, d));
      if (!Files.exists(hashFile)) {
        return false;
      }

      Map<String, String> hashes = new HashMap<>();
      List<String> lines = Files.readAllLines(hashFile);
      if (seed != null && !matchesSeed(lines, seed)) return false;
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
      warning.accept("Failed to check STBN cache validity", e);
      return false;
    }
  }

  public static String seedHeader(long seed) {
    return "# seed " + seed + "\n";
  }

  public static boolean matchesSeed(List<String> lines, long seed) {
    for (String line : lines) {
      if (line.startsWith("# seed ")) {
        try {
          return Long.parseLong(line.substring(7).trim()) == seed;
        } catch (NumberFormatException invalid) {
          return false;
        }
      }
    }
    return false;
  }

  private boolean checkFileHash(int w, int h, int d, int z, String expectedHash) {
    try {
      String baseName = String.format(IMAGE_BASE_FMT, w, h, d, z);
      Path imageFile = directory.resolve(baseName + ".png");

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
      if (hex.length() == 1) hexString.append('0');
      hexString.append(hex);
    }
    return hexString.toString();
  }
}
