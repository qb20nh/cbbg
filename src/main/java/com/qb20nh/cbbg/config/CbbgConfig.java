package com.qb20nh.cbbg.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.qb20nh.cbbg.CbbgClient;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

public final class CbbgConfig {

  public enum Mode {
    ENABLED,
    DISABLED,
    /** Split-screen demo: left = enabled (dither), right = disabled (no dither). */
    DEMO;

    public boolean isActive() {
      return this != DISABLED;
    }
  }

  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
  private static final Path PATH =
      FabricLoader.getInstance().getConfigDir().resolve(CbbgClient.MOD_ID + ".json");

  private static volatile CbbgConfig instance;

  private final Mode mode;

  public static CbbgConfig get() {
    CbbgConfig cfg = instance;
    if (cfg != null) {
      return cfg;
    }
    synchronized (CbbgConfig.class) {
      cfg = instance;
      if (cfg != null) {
        return cfg;
      }
      cfg = load();
      instance = cfg;
      return cfg;
    }
  }

  public static void setMode(Mode mode) {
    if (mode == null) {
      return;
    }
    CbbgConfig next = new CbbgConfig(mode);
    instance = next;
    save(next);
  }

  public CbbgConfig(Mode mode) {
    this.mode = mode == null ? Mode.ENABLED : mode;
  }

  public Mode mode() {
    return mode;
  }

  private static CbbgConfig load() {
    if (!Files.isRegularFile(PATH)) {
      CbbgConfig cfg = new CbbgConfig(Mode.ENABLED);
      save(cfg);
      return cfg;
    }

    try (BufferedReader reader = Files.newBufferedReader(PATH, StandardCharsets.UTF_8)) {
      DiskModel model = GSON.fromJson(reader, DiskModel.class);
      if (model == null || model.mode == null) {
        return new CbbgConfig(Mode.ENABLED);
      }
      return new CbbgConfig(model.mode);
    } catch (JsonParseException e) {
      CbbgClient.LOGGER.warn("Failed to parse {} (resetting to defaults).", PATH, e);
      CbbgConfig cfg = new CbbgConfig(Mode.ENABLED);
      save(cfg);
      return cfg;
    } catch (Exception e) {
      CbbgClient.LOGGER.warn("Failed to read {} (using defaults).", PATH, e);
      return new CbbgConfig(Mode.ENABLED);
    }
  }

  private static void save(CbbgConfig cfg) {
    try {
      Files.createDirectories(PATH.getParent());
      try (BufferedWriter writer = Files.newBufferedWriter(PATH, StandardCharsets.UTF_8)) {
        DiskModel model = new DiskModel();
        model.mode = cfg.mode;
        GSON.toJson(model, writer);
      }
    } catch (Exception e) {
      CbbgClient.LOGGER.warn("Failed to write {}.", PATH, e);
    }
  }

  private static final class DiskModel {
    Mode mode;
  }
}
