package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.platform.NativeImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.NullMarked;

@NullMarked
public final class ReleaseCacheGameTest implements FabricClientGameTest {
  private static final int DEPTH = 8;

  @Override
  public void runTest(ClientGameTestContext context) {
    try (@SuppressWarnings("PMD.UnusedLocalVariable")
        var world = context.worldBuilder().create()) {
      context.waitTicks(5);
      command(context, "mode set enabled");
      command(context, "stbn size 16");
      command(context, "stbn depth " + DEPTH);
      command(context, "stbn seed 42");
      command(context, "stbn generate");
      awaitCache(context, 16);
      Map<Path, Long> original = timestamps(16);

      command(context, "stbn size 32");
      command(context, "mode set enabled");
      awaitCache(context, 32);
      awaitReload(context, 16, "STBN Frames loaded from cache.");
      if (!original.equals(timestamps(16))) {
        throw new AssertionError("Loading a valid cache rewrote its files");
      }

      awaitReload(context, 32, "STBN Frames loaded from cache.");
      try {
        Files.write(image(16, 0), new byte[] {0});
      } catch (IOException failure) {
        throw new AssertionError("Could not damage the cache fixture", failure);
      }
      awaitReload(context, 16, "STBN Images generated from math fields.");
      awaitCache(context, 16);
      if (original.equals(timestamps(16))) {
        throw new AssertionError("A damaged cache was not replaced");
      }

      command(context, "stbn seed 0");
      command(context, "stbn generate");
      awaitPixels(context, "dcf55d060ed318039593d89bb7402af589bc818f6b64b6e90cf99ee33ccc0bcd");
      saveSeedZeroCache();
      command(context, "mode set disabled");
      command(context, "stbn seed 74123");
      command(context, "mode set enabled");
      awaitPixels(context, "4f953f23c7a2a7de8960caa4272090458b2ec986ceeea65796467a0c9109a076");
    }
  }

  private static void awaitPixels(ClientGameTestContext context, String expected) {
    context.waitFor(client -> valid(16) && expected.equals(pixelHash()), 600);
  }

  private static void saveSeedZeroCache() {
    try {
      Path snapshot =
          Path.of(Objects.requireNonNull(System.getProperty("cbbg.test.evidence")))
              .resolve("seed-zero-cache");
      Files.createDirectories(snapshot);
      Files.copy(
          manifest(16), snapshot.resolve(Objects.requireNonNull(manifest(16).getFileName())));
      for (int z = 0; z < DEPTH; z++) {
        Files.copy(
            image(16, z), snapshot.resolve(Objects.requireNonNull(image(16, z).getFileName())));
      }
    } catch (IOException failure) {
      throw new AssertionError("Could not preserve the seed-zero restart cache", failure);
    }
  }

  // CPU reference hashes encode decoded ARGB pixels in z/y/x order, big-endian.
  private static String pixelHash() {
    try {
      ByteBuffer pixels =
          ByteBuffer.allocate(16 * 16 * DEPTH * Integer.BYTES).order(ByteOrder.BIG_ENDIAN);
      for (int z = 0; z < DEPTH; z++) {
        try (NativeImage image =
            NativeImage.read(new ByteArrayInputStream(Files.readAllBytes(image(16, z))))) {
          if (image.getWidth() != 16 || image.getHeight() != 16) {
            throw new AssertionError("Noise cache has incorrect dimensions");
          }
          for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) pixels.putInt(ReleaseImagePixels.argb(image, x, y));
          }
        }
      }
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pixels.array()));
    } catch (IOException incomplete) {
      return "";
    } catch (NoSuchAlgorithmException failure) {
      throw new AssertionError(failure);
    }
  }

  private static void awaitReload(ClientGameTestContext context, int size, String message) {
    int offset = log().length();
    command(context, "stbn size " + size);
    command(context, "mode set enabled");
    context.waitFor(client -> log().substring(offset).contains(message), 600);
  }

  private static void command(ClientGameTestContext context, String command) {
    context.runOnClient(
        client -> Objects.requireNonNull(client.getConnection()).sendCommand("cbbg " + command));
  }

  private static void awaitCache(ClientGameTestContext context, int size) {
    context.waitFor(client -> valid(size), 600);
  }

  private static boolean valid(int size) {
    try {
      Map<String, String> hashes = new TreeMap<>();
      for (String line : Files.readAllLines(manifest(size))) {
        if (line.startsWith("#")) continue;
        String[] parts = line.trim().split("\\s+", 2);
        if (parts.length != 2) return false;
        hashes.put(parts[1], parts[0]);
      }
      if (hashes.size() != DEPTH) return false;
      for (int frame = 0; frame < DEPTH; frame++) {
        Path image = image(size, frame);
        String hash =
            HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(image)));
        if (!hash.equals(hashes.get(Objects.requireNonNull(image.getFileName()).toString())))
          return false;
      }
      return true;
    } catch (IOException incomplete) {
      return false;
    } catch (NoSuchAlgorithmException failure) {
      throw new AssertionError(failure);
    }
  }

  private static Map<Path, Long> timestamps(int size) {
    try {
      Map<Path, Long> timestamps = new TreeMap<>();
      timestamps.put(manifest(size), Files.getLastModifiedTime(manifest(size)).toMillis());
      for (int frame = 0; frame < DEPTH; frame++) {
        Path image = image(size, frame);
        timestamps.put(image, Files.getLastModifiedTime(image).toMillis());
      }
      return timestamps;
    } catch (IOException failure) {
      throw new AssertionError("Could not read cache timestamps", failure);
    }
  }

  private static Path cache() {
    return FabricLoader.getInstance().getGameDir().resolve(".cbbg");
  }

  private static Path manifest(int size) {
    return cache().resolve("stbn_" + size + "x" + size + "x" + DEPTH + ".sha256");
  }

  private static Path image(int size, int frame) {
    return cache().resolve("stbn_" + size + "x" + size + "x" + DEPTH + "_" + frame + ".png");
  }

  private static String log() {
    try {
      return Files.readString(FabricLoader.getInstance().getGameDir().resolve("logs/latest.log"));
    } catch (IOException failure) {
      throw new AssertionError("Could not read the client log", failure);
    }
  }
}
