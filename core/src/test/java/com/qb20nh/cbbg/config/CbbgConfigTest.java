package com.qb20nh.cbbg.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CbbgConfigTest {

  @Test
  void constructor_nullMode_defaultsToEnabled() {
    CbbgConfig cfg =
        new CbbgConfig(null, CbbgConfig.PixelFormat.RGBA16F, 128, 64, 0L, 1.0f, true, true);
    Assertions.assertEquals(CbbgConfig.Mode.ENABLED, cfg.mode());
  }

  @Test
  void constructor_nullPixelFormat_defaultsToRgba16f() {
    CbbgConfig cfg = new CbbgConfig(CbbgConfig.Mode.ENABLED, null, 128, 64, 0L, 1.0f, true, true);
    Assertions.assertEquals(CbbgConfig.PixelFormat.RGBA16F, cfg.pixelFormat());
  }

  @Test
  void constructor_rgba8PixelFormat_normalizesToRgba16f() {
    CbbgConfig cfg =
        new CbbgConfig(
            CbbgConfig.Mode.ENABLED, CbbgConfig.PixelFormat.RGBA8, 128, 64, 0L, 1.0f, true, true);
    Assertions.assertEquals(CbbgConfig.PixelFormat.RGBA16F, cfg.pixelFormat());
  }

  @Test
  void constructor_clampsStbnSizeAndDepth() {
    CbbgConfig cfg =
        new CbbgConfig(
            CbbgConfig.Mode.ENABLED, CbbgConfig.PixelFormat.RGBA16F, -5, 999, 0L, 1.0f, true, true);
    Assertions.assertEquals(16, cfg.stbnSize());
    Assertions.assertEquals(128, cfg.stbnDepth());
  }

  @Test
  void constructor_clampsStrengthAndHandlesNaNInfinity() {
    Assertions.assertEquals(
        0.5f,
        new CbbgConfig(
                CbbgConfig.Mode.ENABLED,
                CbbgConfig.PixelFormat.RGBA16F,
                128,
                64,
                0L,
                -999.0f,
                true,
                true)
            .strength(),
        0.0f);
    Assertions.assertEquals(
        4.0f,
        new CbbgConfig(
                CbbgConfig.Mode.ENABLED,
                CbbgConfig.PixelFormat.RGBA16F,
                128,
                64,
                0L,
                999.0f,
                true,
                true)
            .strength(),
        0.0f);
    Assertions.assertEquals(
        1.0f,
        new CbbgConfig(
                CbbgConfig.Mode.ENABLED,
                CbbgConfig.PixelFormat.RGBA16F,
                128,
                64,
                0L,
                Float.NaN,
                true,
                true)
            .strength(),
        0.0f);
    Assertions.assertEquals(
        1.0f,
        new CbbgConfig(
                CbbgConfig.Mode.ENABLED,
                CbbgConfig.PixelFormat.RGBA16F,
                128,
                64,
                0L,
                Float.POSITIVE_INFINITY,
                true,
                true)
            .strength(),
        0.0f);
  }

  @Test
  void mode_isActiveMatchesDisabledOnly() {
    Assertions.assertTrue(CbbgConfig.Mode.ENABLED.isActive());
    Assertions.assertTrue(CbbgConfig.Mode.DEMO.isActive());
    Assertions.assertFalse(CbbgConfig.Mode.DISABLED.isActive());
  }

  @Test
  void enum_serializedNames_matchExpectedValues() {
    Assertions.assertEquals("enabled", CbbgConfig.Mode.ENABLED.getSerializedName());
    Assertions.assertEquals("disabled", CbbgConfig.Mode.DISABLED.getSerializedName());
    Assertions.assertEquals("demo", CbbgConfig.Mode.DEMO.getSerializedName());

    Assertions.assertEquals("rgba8", CbbgConfig.PixelFormat.RGBA8.getSerializedName());
    Assertions.assertEquals("rgba16f", CbbgConfig.PixelFormat.RGBA16F.getSerializedName());
    Assertions.assertEquals("rgba32f", CbbgConfig.PixelFormat.RGBA32F.getSerializedName());
  }

  private static final Gson LEGACY_GSON = new GsonBuilder().setPrettyPrinting().create();

  @Test
  void streamingCodec_matchesReflectiveCodecOnInputs(@TempDir Path directory) throws IOException {
    String[] inputs = {
      "{}",
      "  ",
      "null",
      "[]",
      "true",
      "{} {}",
      "{\"mode\":\"DEMO\",\"pixelFormat\":\"RGBA32F\"}",
      "{\"mode\":\"demo\",\"pixelFormat\":\"rgba16f\"}",
      "{\"mode\":7,\"pixelFormat\":false}",
      "{\"mode\":null,\"pixelFormat\":\"RGBA8\"}",
      "{\"stbnSize\":null,\"stbnDepth\":null,\"stbnSeed\":null}",
      "{\"stbnSize\":\"12\",\"stbnDepth\":128.0,\"stbnSeed\":\"-19\"}",
      "{\"stbnSize\":1.5}",
      "{\"stbnSeed\":\"bad\"}",
      "{\"strength\":null}",
      "{\"strength\":\"3.5\"}",
      "{\"strength\":\"bad\"}",
      "{\"strength\":1e40}",
      "{\"notifyChat\":null,\"notifyToast\":null}",
      "{\"notifyChat\":\"TRUE\",\"notifyToast\":\"anything\"}",
      "{\"notifyChat\":0}",
      "{\"notifyChat\":false}",
      "{\"unknown\":{\"nested\":[1,true,null]}}",
      "{\"mode\":\"DISABLED\",\"mode\":\"ENABLED\"}",
      "{\"stbnSize\":32,\"stbnSize\":null}",
      "{mode:'DEMO', stbnSize: 32}",
      "{\"mode\":\"DEMO\"",
      "{\"mode\":}",
      "{\"mode\":\"DEMO\"} trailing"
    };
    Path path = directory.resolve("config.json");
    for (String input : inputs) {
      Files.write(path, input.getBytes(StandardCharsets.UTF_8));
      ReferenceResult expected = legacyRead(input);
      List<String> warnings = new ArrayList<>();
      CbbgConfig actual = CbbgConfig.load(path, (message, cause) -> warnings.add(message));
      Assertions.assertEquals(expected.config, actual, input);
      Assertions.assertEquals(expected.warning, warningKind(warnings), input);
      String expectedDisk = expected.warning.equals("parse") ? legacyJson(expected.config) : input;
      Assertions.assertEquals(
          expectedDisk, new String(Files.readAllBytes(path), StandardCharsets.UTF_8), input);
    }
  }

  @Test
  void streamingCodec_matchesReflectivePrettyPrint(@TempDir Path directory) throws IOException {
    CbbgConfig[] configs = {
      new CbbgConfig(CbbgConfig.Mode.ENABLED),
      new CbbgConfig(
          CbbgConfig.Mode.DEMO,
          CbbgConfig.PixelFormat.RGBA32F,
          16,
          128,
          Long.MIN_VALUE,
          0.5f,
          false,
          true)
    };
    Path path = directory.resolve("config.json");
    for (CbbgConfig config : configs) {
      List<String> warnings = new ArrayList<>();
      CbbgConfig.save(path, config, (message, cause) -> warnings.add(message));
      Assertions.assertTrue(warnings.isEmpty());
      Assertions.assertEquals(
          legacyJson(config), new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
    }
  }

  @Test
  void load_missingFileWritesDefaultsAndWriteFailureWarns(@TempDir Path directory)
      throws IOException {
    Path path = directory.resolve("nested/config.json");
    List<String> warnings = new ArrayList<>();
    CbbgConfig defaults = CbbgConfig.load(path, (message, cause) -> warnings.add(message));
    Assertions.assertEquals(new CbbgConfig(CbbgConfig.Mode.ENABLED), defaults);
    Assertions.assertTrue(warnings.isEmpty());
    Assertions.assertEquals(
        legacyJson(defaults), new String(Files.readAllBytes(path), StandardCharsets.UTF_8));

    CbbgConfig.save(directory, defaults, (message, cause) -> warnings.add(message));
    Assertions.assertEquals(1, warnings.size());
    Assertions.assertTrue(warnings.get(0).startsWith("Failed to write "));
  }

  private static ReferenceResult legacyRead(String input) {
    try {
      LegacyDiskModel model = LEGACY_GSON.fromJson(input, LegacyDiskModel.class);
      return new ReferenceResult(
          new CbbgConfig(
              model.mode,
              model.pixelFormat,
              model.stbnSize,
              model.stbnDepth,
              model.stbnSeed,
              model.strength == null ? 1.0f : model.strength.floatValue(),
              model.notifyChat,
              model.notifyToast),
          "none");
    } catch (JsonParseException e) {
      return new ReferenceResult(new CbbgConfig(CbbgConfig.Mode.ENABLED), "parse");
    } catch (Exception e) {
      return new ReferenceResult(new CbbgConfig(CbbgConfig.Mode.ENABLED), "read");
    }
  }

  private static String warningKind(List<String> warnings) {
    if (warnings.isEmpty()) return "none";
    Assertions.assertEquals(1, warnings.size());
    return warnings.get(0).startsWith("Failed to parse ") ? "parse" : "read";
  }

  private static String legacyJson(CbbgConfig config) {
    LegacyDiskModel model = new LegacyDiskModel();
    model.mode = config.mode();
    model.pixelFormat = config.pixelFormat();
    model.stbnSize = config.stbnSize();
    model.stbnDepth = config.stbnDepth();
    model.stbnSeed = config.stbnSeed();
    model.strength = config.strength();
    model.notifyChat = config.notifyChat();
    model.notifyToast = config.notifyToast();
    return LEGACY_GSON.toJson(model);
  }

  private static final class ReferenceResult {
    final CbbgConfig config;
    final String warning;

    ReferenceResult(CbbgConfig config, String warning) {
      this.config = config;
      this.warning = warning;
    }
  }

  private static final class LegacyDiskModel {
    CbbgConfig.Mode mode;
    CbbgConfig.PixelFormat pixelFormat;
    int stbnSize = 128;
    int stbnDepth = 64;
    long stbnSeed;
    Float strength;
    boolean notifyChat = true;
    boolean notifyToast = true;
  }
}
