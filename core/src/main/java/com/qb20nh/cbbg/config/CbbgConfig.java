package com.qb20nh.cbbg.config;

import com.qb20nh.cbbg.internal.gson.stream.JsonReader;
import com.qb20nh.cbbg.internal.gson.stream.JsonToken;
import com.qb20nh.cbbg.internal.gson.stream.JsonWriter;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import java.util.Objects;
import java.util.function.BiConsumer;

public final class CbbgConfig {
    private final Mode mode;
    private final PixelFormat pixelFormat;
    private final int stbnSize;
    private final int stbnDepth;
    private final long stbnSeed;
    private final float strength;
    private final boolean notifyChat;
    private final boolean notifyToast;


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

    private static Path path;
    private static BiConsumer<String, Throwable> warning;

    private static volatile CbbgConfig instance;

    /** Called by the loader before any renderer or settings UI accesses the singleton. */
    public static synchronized void configure(Path configFile, BiConsumer<String, Throwable> logger) {
        Path normalized = Objects.requireNonNull(configFile, "configFile").toAbsolutePath().normalize();
        Objects.requireNonNull(logger, "logger");
        if (path != null && !path.equals(normalized)) {
            throw new IllegalStateException("CBBG configuration already initialized at " + path);
        }
        path = normalized;
        warning = logger;
    }

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
            if (path == null) {
                throw new IllegalStateException("The loader must configure CBBG before accessing settings");
            }
            cfg = load(path, warning);
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
        this.stbnSize = Math.max(16, Math.min(stbnSize, 256));
        this.stbnDepth = Math.max(8, Math.min(stbnDepth, 128));
        this.stbnSeed = stbnSeed;
        this.strength = clampStrength(strength);
        this.notifyChat = notifyChat;
        this.notifyToast = notifyToast;
    }

    public CbbgConfig(Mode mode) {
        this(mode, PixelFormat.RGBA16F, 128, 64, 0, 1.0f, true, true);
    }

    static CbbgConfig load(Path path, BiConsumer<String, Throwable> warning) {
        if (!Files.isRegularFile(path)) {
            CbbgConfig cfg = new CbbgConfig(Mode.ENABLED);
            save(path, cfg, warning);
            return cfg;
        }

        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return read(reader);
        } catch (ParseFailureException e) {
            warning.accept("Failed to parse " + path + " (resetting to defaults).", e);
            CbbgConfig cfg = new CbbgConfig(Mode.ENABLED);
            save(path, cfg, warning);
            return cfg;
        } catch (Exception e) {
            warning.accept("Failed to read " + path + " (using defaults).", e);
            return new CbbgConfig(Mode.ENABLED);
        }
    }

    private static void save(CbbgConfig cfg) {
        save(path, cfg, warning);
    }

    static void save(Path path, CbbgConfig cfg, BiConsumer<String, Throwable> warning) {
        try {
            Files.createDirectories(path.toAbsolutePath().getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                JsonWriter json = new JsonWriter(writer);
                json.setIndent("  ");
                json.setHtmlSafe(true);
                json.beginObject();
                json.name("mode").value(cfg.mode().name());
                json.name("pixelFormat").value(cfg.pixelFormat().name());
                json.name("stbnSize").value(cfg.stbnSize());
                json.name("stbnDepth").value(cfg.stbnDepth());
                json.name("stbnSeed").value(cfg.stbnSeed());
                json.name("strength").value(Float.valueOf(cfg.strength()));
                json.name("notifyChat").value(cfg.notifyChat());
                json.name("notifyToast").value(cfg.notifyToast());
                json.endObject();
            }
        } catch (Exception e) {
            warning.accept("Failed to write " + path + ".", e);
        }
    }

    private static float clampStrength(float strength) {
        if (Float.isNaN(strength) || Float.isInfinite(strength)) {
            return 1.0f;
        }
        return Math.min(4.0f, Math.max(0.5f, strength));
    }

    private static CbbgConfig read(BufferedReader input) throws IOException {
        JsonReader json = new JsonReader(input);
        json.setLenient(true);
        try {
            // Gson's fromJson returns null for an empty document, which the old load path
            // reports as a read failure rather than resetting the file.
            try {
                json.peek();
            } catch (EOFException e) {
                throw new EmptyDocumentException(e);
            }
            if (json.peek() == JsonToken.NULL) {
                json.nextNull();
                throw new EmptyDocumentException(null);
            }

            Mode mode = null;
            PixelFormat pixelFormat = null;
            int stbnSize = 128;
            int stbnDepth = 64;
            long stbnSeed = 0;
            float strength = 1.0f;
            boolean notifyChat = true;
            boolean notifyToast = true;

            json.beginObject();
            while (json.hasNext()) {
                String name = json.nextName();
                if ("mode".equals(name)) {
                    if (json.peek() == JsonToken.NULL) {
                        json.nextNull();
                        mode = null;
                    } else {
                        mode = modeValue(json.nextString());
                    }
                } else if ("pixelFormat".equals(name)) {
                    if (json.peek() == JsonToken.NULL) {
                        json.nextNull();
                        pixelFormat = null;
                    } else {
                        pixelFormat = pixelFormatValue(json.nextString());
                    }
                } else if ("stbnSize".equals(name)) {
                    if (json.peek() == JsonToken.NULL) json.nextNull();
                    else stbnSize = readInt(json);
                } else if ("stbnDepth".equals(name)) {
                    if (json.peek() == JsonToken.NULL) json.nextNull();
                    else stbnDepth = readInt(json);
                } else if ("stbnSeed".equals(name)) {
                    if (json.peek() == JsonToken.NULL) json.nextNull();
                    else stbnSeed = readLong(json);
                } else if ("strength".equals(name)) {
                    if (json.peek() == JsonToken.NULL) {
                        json.nextNull();
                        strength = 1.0f;
                    } else {
                        strength = (float) json.nextDouble();
                    }
                } else if ("notifyChat".equals(name)) {
                    if (json.peek() == JsonToken.NULL) json.nextNull();
                    else notifyChat = readBoolean(json);
                } else if ("notifyToast".equals(name)) {
                    if (json.peek() == JsonToken.NULL) json.nextNull();
                    else notifyToast = readBoolean(json);
                } else {
                    json.skipValue();
                }
            }
            json.endObject();
            // Gson checks for trailing content with lenient mode restored to false.
            json.setLenient(false);
            if (json.peek() != JsonToken.END_DOCUMENT) {
                throw new TrailingContentException();
            }
            return new CbbgConfig(mode, pixelFormat, stbnSize, stbnDepth, stbnSeed, strength,
                    notifyChat, notifyToast);
        } catch (EmptyDocumentException | TrailingContentException e) {
            throw e;
        } catch (IOException | IllegalStateException e) {
            throw new ParseFailureException(e);
        }
    }

    private static int readInt(JsonReader json) throws IOException {
        try {
            return json.nextInt();
        } catch (NumberFormatException e) {
            throw new ParseFailureException(e);
        }
    }

    private static long readLong(JsonReader json) throws IOException {
        try {
            return json.nextLong();
        } catch (NumberFormatException e) {
            throw new ParseFailureException(e);
        }
    }

    private static boolean readBoolean(JsonReader json) throws IOException {
        if (json.peek() == JsonToken.STRING) {
            return Boolean.parseBoolean(json.nextString());
        }
        return json.nextBoolean();
    }

    private static Mode modeValue(String name) {
        for (Mode value : Mode.values()) {
            if (value.name().equals(name)) return value;
        }
        return null;
    }

    private static PixelFormat pixelFormatValue(String name) {
        for (PixelFormat value : PixelFormat.values()) {
            if (value.name().equals(name)) return value;
        }
        return null;
    }

    private static final class ParseFailureException extends RuntimeException {
        ParseFailureException(Throwable cause) { super(cause); }
    }

    private static final class EmptyDocumentException extends RuntimeException {
        EmptyDocumentException(Throwable cause) { super(cause); }
    }

    private static final class TrailingContentException extends RuntimeException {}

    public Mode mode() { return mode; }
    public PixelFormat pixelFormat() { return pixelFormat; }
    public int stbnSize() { return stbnSize; }
    public int stbnDepth() { return stbnDepth; }
    public long stbnSeed() { return stbnSeed; }
    public float strength() { return strength; }
    public boolean notifyChat() { return notifyChat; }
    public boolean notifyToast() { return notifyToast; }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof CbbgConfig)) return false;
        CbbgConfig that = (CbbgConfig) other;
        return mode == that.mode && pixelFormat == that.pixelFormat
                && stbnSize == that.stbnSize && stbnDepth == that.stbnDepth
                && stbnSeed == that.stbnSeed && Float.compare(strength, that.strength) == 0
                && notifyChat == that.notifyChat && notifyToast == that.notifyToast;
    }

    @Override
    public int hashCode() {
        int result = mode.hashCode();
        result = 31 * result + pixelFormat.hashCode();
        result = 31 * result + stbnSize;
        result = 31 * result + stbnDepth;
        result = 31 * result + Long.hashCode(stbnSeed);
        result = 31 * result + Float.hashCode(strength);
        result = 31 * result + Boolean.hashCode(notifyChat);
        return 31 * result + Boolean.hashCode(notifyToast);
    }

    @Override
    public String toString() {
        return "CbbgConfig[mode=" + mode + ", pixelFormat=" + pixelFormat + ", stbnSize="
                + stbnSize + ", stbnDepth=" + stbnDepth + ", stbnSeed=" + stbnSeed
                + ", strength=" + strength + ", notifyChat=" + notifyChat
                + ", notifyToast=" + notifyToast + "]";
    }
}
