package com.qb20nh.cbbg.config;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CbbgConfigPersistenceTest {
  @TempDir Path directory;

  @Test
  void missingFileWritesDefaultsAtInjectedPath() throws Exception {
    Path path = directory.resolve("config/cbbg.json");
    List<String> warnings = new ArrayList<>();
    CbbgConfig config = CbbgConfig.load(path, (message, error) -> warnings.add(message));
    assertEquals(new CbbgConfig(CbbgConfig.Mode.ENABLED), config);
    assertTrue(Files.isRegularFile(path));
    assertTrue(warnings.isEmpty());
    assertEquals(config, CbbgConfig.load(path, (message, error) -> fail(message)));
  }

  @Test
  void allSettingsRoundTripUsingExistingDiskNames() throws Exception {
    Path path = directory.resolve("cbbg.json");
    CbbgConfig config =
        new CbbgConfig(
            CbbgConfig.Mode.DEMO,
            CbbgConfig.PixelFormat.RGBA32F,
            64,
            32,
            Long.MIN_VALUE,
            2.5f,
            false,
            false);
    CbbgConfig.save(path, config, (message, error) -> fail(message));
    CbbgConfig loaded = CbbgConfig.load(path, (message, error) -> fail(message));
    assertEquals(config, loaded);
    assertEquals(config.hashCode(), loaded.hashCode());
    String json = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    assertTrue(json.contains("\"mode\": \"DEMO\""));
    assertTrue(json.contains("\"pixelFormat\": \"RGBA32F\""));
    assertTrue(json.contains("\"stbnSeed\": -9223372036854775808"));
  }

  @Test
  void oldPartialConfigKeepsNewFieldDefaults() throws Exception {
    Path path = write("{\"mode\":\"DISABLED\"}");
    assertEquals(
        new CbbgConfig(CbbgConfig.Mode.DISABLED),
        CbbgConfig.load(path, (message, error) -> fail(message)));
  }

  @Test
  void malformedConfigIsResetButNullDocumentUsesDefaultsWithoutRewriting() throws Exception {
    List<String> warnings = new ArrayList<>();
    Path path = write("{ invalid json");
    assertEquals(
        new CbbgConfig(CbbgConfig.Mode.ENABLED),
        CbbgConfig.load(path, (message, error) -> warnings.add(message)));
    assertEquals(1, warnings.size());
    assertEquals(
        new CbbgConfig(CbbgConfig.Mode.ENABLED),
        CbbgConfig.load(path, (message, error) -> fail(message)));
    Files.write(path, "null".getBytes(StandardCharsets.UTF_8));
    CbbgConfig.load(path, (message, error) -> warnings.add(message));
    assertEquals("null", new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
    assertEquals(2, warnings.size());
  }

  @Test
  void writeFailureReportsWarningAndDoesNotThrow() {
    List<String> warnings = new ArrayList<>();
    CbbgConfig.save(
        directory,
        new CbbgConfig(CbbgConfig.Mode.ENABLED),
        (message, error) -> warnings.add(message));
    assertEquals(1, warnings.size());
  }

  private Path write(String json) throws Exception {
    Path path = directory.resolve("cbbg.json");
    Files.write(path, json.getBytes(StandardCharsets.UTF_8));
    return path;
  }
}
