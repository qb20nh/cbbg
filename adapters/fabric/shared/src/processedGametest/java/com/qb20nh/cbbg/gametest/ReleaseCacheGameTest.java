package com.qb20nh.cbbg.gametest;

import java.io.IOException;
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
    try (var _ = context.worldBuilder().create()) {
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
