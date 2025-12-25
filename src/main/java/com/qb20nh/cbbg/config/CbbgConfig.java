package com.qb20nh.cbbg.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.qb20nh.cbbg.Cbbg;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import net.fabricmc.loader.api.FabricLoader;

public record CbbgConfig(Mode mode, PixelFormat pixelFormat, int stbnSize, int stbnDepth,
        long stbnSeed, float strength, boolean notifyChat, boolean notifyToast) {


    public enum Mode {
        ENABLED("enabled"), DISABLED("disabled"),
        /** Split-screen demo: left = enabled (dither), right = disabled (no dither). */
        DEMO("demo");

        private final String name;

        Mode(String name) {
            this.name = name;
        }

        public boolean isActive() {
            return this != DISABLED;
        }

        public String getSerializedName() {
            return this.name;
        }
    }

    public enum PixelFormat {
        RGBA8("rgba8"), RGBA16F("rgba16f"), RGBA32F("rgba32f");

        private final String name;

        PixelFormat(String name) {
            this.name = name;
        }

        public String getSerializedName() {
            return this.name;
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH =
            FabricLoader.getInstance().getConfigDir().resolve(Cbbg.MOD_ID + ".json");

    private static volatile CbbgConfig instance;

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

    public static synchronized void setMode(Mode mode) {
        if (mode == null) {
            return;
        }
        CbbgConfig current = get();
        CbbgConfig next = new CbbgConfig(mode, current.pixelFormat, current.stbnSize,
                current.stbnDepth, current.stbnSeed, current.strength, current.notifyChat,
                current.notifyToast);
        instance = next;
        save(next);
    }

    public static synchronized void setPixelFormat(PixelFormat format) {
        if (format == null) {
            return;
        }
        CbbgConfig current = get();
        CbbgConfig next = new CbbgConfig(current.mode, format, current.stbnSize, current.stbnDepth,
                current.stbnSeed, current.strength, current.notifyChat, current.notifyToast);
        instance = next;
        save(next);
    }

    public static synchronized void setStbnSize(int size) {
        CbbgConfig current = get();
        CbbgConfig next = new CbbgConfig(current.mode, current.pixelFormat, size, current.stbnDepth,
                current.stbnSeed, current.strength, current.notifyChat, current.notifyToast);
        instance = next;
        save(next);
    }

    public static synchronized void setStbnDepth(int depth) {
        CbbgConfig current = get();
        CbbgConfig next = new CbbgConfig(current.mode, current.pixelFormat, current.stbnSize, depth,
                current.stbnSeed, current.strength, current.notifyChat, current.notifyToast);
        instance = next;
        save(next);
    }

    public static synchronized void setStbnSeed(long seed) {
        CbbgConfig current = get();
        CbbgConfig next = new CbbgConfig(current.mode, current.pixelFormat, current.stbnSize,
                current.stbnDepth, seed, current.strength, current.notifyChat, current.notifyToast);
        instance = next;
        save(next);
    }

    public static synchronized void setStrength(float strength) {
        CbbgConfig current = get();
        CbbgConfig next = new CbbgConfig(current.mode, current.pixelFormat, current.stbnSize,
                current.stbnDepth, current.stbnSeed, strength, current.notifyChat,
                current.notifyToast);
        instance = next;
        save(next);
    }

    public static synchronized void setNotifyChat(boolean notify) {
        CbbgConfig current = get();
        CbbgConfig next = new CbbgConfig(current.mode, current.pixelFormat, current.stbnSize,
                current.stbnDepth, current.stbnSeed, current.strength, notify, current.notifyToast);
        instance = next;
        save(next);
    }

    public static synchronized void setNotifyToast(boolean notify) {
        CbbgConfig current = get();
        CbbgConfig next = new CbbgConfig(current.mode, current.pixelFormat, current.stbnSize,
                current.stbnDepth, current.stbnSeed, current.strength, current.notifyChat, notify);
        instance = next;
        save(next);
    }


    public CbbgConfig(Mode mode, PixelFormat pixelFormat, int stbnSize, int stbnDepth,
            long stbnSeed, float strength, boolean notifyChat, boolean notifyToast) {
        this.mode = mode == null ? Mode.ENABLED : mode;
        // RGBA8 is not user-selectable; treat it as a legacy/invalid value and normalize to
        // RGBA16F.
        this.pixelFormat =
                pixelFormat == null || pixelFormat == PixelFormat.RGBA8 ? PixelFormat.RGBA16F
                        : pixelFormat;
        this.stbnSize = Math.clamp(stbnSize, 16, 256);
        this.stbnDepth = Math.clamp(stbnDepth, 8, 128);
        this.stbnSeed = stbnSeed;
        this.strength = clampStrength(strength);
        this.notifyChat = notifyChat;
        this.notifyToast = notifyToast;
    }

    public CbbgConfig(Mode mode) {
        this(mode, PixelFormat.RGBA16F, 128, 64, 0, 1.0f, true, true);
    }

    private static CbbgConfig load() {
        if (!Files.isRegularFile(PATH)) {
            CbbgConfig cfg = new CbbgConfig(Mode.ENABLED);
            save(cfg);
            return cfg;
        }

        try (BufferedReader reader = Files.newBufferedReader(PATH, StandardCharsets.UTF_8)) {
            DiskModel model = GSON.fromJson(reader, DiskModel.class);
            float strength = model.strength == null ? 1.0f : model.strength.floatValue();
            return new CbbgConfig(model.mode, model.pixelFormat, model.stbnSize, model.stbnDepth,
                    model.stbnSeed, strength, model.notifyChat, model.notifyToast);
        } catch (JsonParseException e) {
            Cbbg.LOGGER.warn("Failed to parse {} (resetting to defaults).", PATH, e);
            CbbgConfig cfg = new CbbgConfig(Mode.ENABLED);
            save(cfg);
            return cfg;
        } catch (Exception e) {
            Cbbg.LOGGER.warn("Failed to read {} (using defaults).", PATH, e);
            return new CbbgConfig(Mode.ENABLED);
        }
    }

    private static void save(CbbgConfig cfg) {
        try {
            Files.createDirectories(PATH.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(PATH, StandardCharsets.UTF_8)) {
                DiskModel model = new DiskModel();
                model.mode = cfg.mode();
                model.pixelFormat = cfg.pixelFormat();
                model.stbnSize = cfg.stbnSize();
                model.stbnDepth = cfg.stbnDepth();
                model.stbnSeed = cfg.stbnSeed();
                model.strength = cfg.strength();
                model.notifyChat = cfg.notifyChat();
                model.notifyToast = cfg.notifyToast();
                GSON.toJson(model, writer);
            }
        } catch (Exception e) {
            Cbbg.LOGGER.warn("Failed to write {}.", PATH, e);
        }
    }

    private static float clampStrength(float strength) {
        if (Float.isNaN(strength) || Float.isInfinite(strength)) {
            return 1.0f;
        }
        return Math.min(4.0f, Math.max(0.5f, strength));
    }

    private static final class DiskModel {
        Mode mode;
        PixelFormat pixelFormat;
        int stbnSize = 128;
        int stbnDepth = 64;
        long stbnSeed = 0;
        Float strength;
        boolean notifyChat = true;
        boolean notifyToast = true;
    }
}
