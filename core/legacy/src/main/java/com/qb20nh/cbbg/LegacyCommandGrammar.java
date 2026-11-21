package com.qb20nh.cbbg;

import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.config.CbbgConfig.Mode;
import com.qb20nh.cbbg.config.CbbgConfig.PixelFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** Client-only command grammar for games predating Brigadier. */
@NullMarked
public final class LegacyCommandGrammar {
  private static final String COMMAND = "/cbbg";
  private static final String FORMAT = "format";
  private static final String NOTIFICATION = "notification";
  private static final String TOAST = "toast";
  private static final String DEPTH = "depth";
  private static final String GENERATE = "generate";
  private static final String RESET = "reset";
  private static final String CURRENT_SUFFIX = ".current";
  private static final String USAGE_SUFFIX = ".usage";
  private static final String STBN_PREFIX = "stbn.";
  private static final String NOTIFICATION_PREFIX = "notification.";

  private LegacyCommandGrammar() {}

  public interface Feedback {
    Object translate(String key);

    void send(boolean error, String key, Object... args);
  }

  /** Null leaves completion to vanilla; an empty list claims an invalid CBBG argument. */
  public static @Nullable List<String> suggest(String message, boolean toast) {
    String[] args = message.split("\\s+", -1);
    if (!args[0].equals(COMMAND)) return null;
    List<String> candidates = new ArrayList<>();
    if (args.length == 1) candidates.add(COMMAND);
    else if (args.length == 2) {
      java.util.Collections.addAll(candidates, "help", "mode", FORMAT, "stbn", NOTIFICATION);
    } else if (args.length == 3) {
      suggestAction(candidates, args[1], toast);
    } else if (args.length == 4) {
      suggestValue(candidates, args[1], args[2], toast);
    }
    String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
    candidates.removeIf(candidate -> !candidate.startsWith(prefix));
    return candidates;
  }

  private static void suggestAction(List<String> candidates, String kind, boolean toast) {
    if (kind.equals("mode") || kind.equals(FORMAT)) candidates.add("set");
    else if (kind.equals("stbn")) {
      java.util.Collections.addAll(candidates, GENERATE, "size", DEPTH, "seed", RESET);
    } else if (kind.equals(NOTIFICATION)) {
      candidates.add("chat");
      if (toast) candidates.add(TOAST);
    }
  }

  private static void suggestValue(
      List<String> candidates, String kind, String action, boolean toast) {
    if (kind.equals("mode") && action.equals("set")) {
      for (Mode mode : Mode.values()) candidates.add(mode.getSerializedName());
    } else if (kind.equals(FORMAT) && action.equals("set")) {
      for (PixelFormat format : PixelFormat.values()) {
        if (format != PixelFormat.RGBA8) candidates.add(format.getSerializedName());
      }
    } else if (kind.equals(NOTIFICATION)
        && (action.equals("chat") || (action.equals(TOAST) && toast))) {
      java.util.Collections.addAll(candidates, "true", "false");
    }
  }

  public static boolean execute(
      String message, boolean toast, Feedback feedback, Runnable regenerate, Runnable reload) {
    String[] args = message.trim().split("\\s+");
    if (!args[0].equals(COMMAND)) return false;
    if (args.length == 1 || (args.length == 2 && args[1].equals("help"))) {
      for (String key :
          new String[] {"header", "mode", "mode_set", FORMAT, "format_set", "stbn", NOTIFICATION}) {
        send(feedback, false, "root.help." + key);
      }
    } else if (args[1].equals("mode") || args[1].equals(FORMAT)) {
      selection(args, feedback);
    } else if (args[1].equals("stbn")) {
      noise(args, feedback, regenerate, reload);
    } else if (args[1].equals(NOTIFICATION)) {
      notification(args, toast, feedback);
    } else {
      usage(feedback);
    }
    return true;
  }

  private static void selection(String[] args, Feedback feedback) {
    String kind = args[1];
    if (args.length == 2) {
      send(feedback, false, kind + CURRENT_SUFFIX, name(kind, feedback));
      return;
    }
    if (args.length != 4 || !args[2].equals("set")) {
      send(feedback, true, kind + USAGE_SUFFIX);
      return;
    }
    String value = args[3];
    if (value.length() > 1 && value.startsWith("\"") && value.endsWith("\"")) {
      value = value.substring(1, value.length() - 1);
    }
    if (kind.equals("mode")) {
      Mode mode;
      try {
        mode = Mode.valueOf(value.toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException invalid) {
        send(feedback, true, "mode.invalid", value);
        return;
      }
      CbbgConfig.setMode(mode);
    } else {
      PixelFormat format;
      try {
        format = PixelFormat.valueOf(value.toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException invalid) {
        send(feedback, true, "format.invalid", value);
        return;
      }
      if (format == PixelFormat.RGBA8) {
        send(feedback, true, "format.rgba8_not_selectable");
        return;
      }
      CbbgConfig.setPixelFormat(format);
    }
    // The render-thread tick applies mode/precision changes without regenerating noise.
    send(feedback, false, kind + ".set", name(kind, feedback));
  }

  private static Object name(String kind, Feedback feedback) {
    return kind.equals("mode")
        ? feedback.translate("cbbg.mode." + CbbgConfig.get().mode().getSerializedName())
        : feedback.translate(
            "cbbg.pixel_format." + CbbgConfig.get().pixelFormat().getSerializedName());
  }

  private static void noise(
      String[] args, Feedback feedback, Runnable regenerate, Runnable reload) {
    if (args.length == 2) {
      CbbgConfig config = CbbgConfig.get();
      send(
          feedback,
          false,
          "stbn.usage.header",
          config.stbnSize(),
          config.stbnDepth(),
          config.stbnSeed());
      for (String key : new String[] {GENERATE, "size", DEPTH, "seed", RESET}) {
        send(feedback, false, "stbn.usage." + key);
      }
      return;
    }
    String kind = args[2];
    if (args.length == 3 && kind.equals(GENERATE)) {
      send(feedback, false, "stbn.generating");
      regenerate.run();
    } else if (args.length == 3 && kind.equals(RESET)) {
      CbbgConfig.setStbnSize(128);
      CbbgConfig.setStbnDepth(64);
      CbbgConfig.setStbnSeed(0);
      reload.run();
      send(feedback, false, "stbn.reset");
    } else if (kind.equals("size") || kind.equals(DEPTH) || kind.equals("seed")) {
      noiseSetting(args, kind, feedback);
    } else usage(feedback);
  }

  private static void noiseSetting(String[] args, String kind, Feedback feedback) {
    if (args.length == 3) {
      CbbgConfig config = CbbgConfig.get();
      long value =
          kind.equals("size")
              ? config.stbnSize()
              : kind.equals(DEPTH) ? config.stbnDepth() : config.stbnSeed();
      send(feedback, false, STBN_PREFIX + kind + CURRENT_SUFFIX, value);
      send(feedback, false, STBN_PREFIX + kind + USAGE_SUFFIX);
      return;
    }
    if (args.length != 4) {
      usage(feedback);
      return;
    }
    long value;
    try {
      value = Long.parseLong(args[3]);
    } catch (NumberFormatException invalid) {
      send(feedback, true, STBN_PREFIX + kind + USAGE_SUFFIX);
      return;
    }
    if (kind.equals("seed")) {
      CbbgConfig.setStbnSeed(value);
    } else if (!setNoiseDimension(kind, value, feedback)) {
      return;
    }
    send(feedback, false, STBN_PREFIX + kind + ".set", value);
  }

  private static boolean setNoiseDimension(String kind, long value, Feedback feedback) {
    boolean size = kind.equals("size");
    int minimum = size ? 16 : 8;
    int maximum = size ? 256 : 128;
    if (value < minimum || value > maximum || Long.bitCount(value) != 1) {
      send(feedback, true, STBN_PREFIX + kind + ".invalid_pow2");
      send(feedback, true, STBN_PREFIX + kind + USAGE_SUFFIX);
      return false;
    }
    if (size) CbbgConfig.setStbnSize((int) value);
    else CbbgConfig.setStbnDepth((int) value);
    return true;
  }

  private static void notification(String[] args, boolean toast, Feedback feedback) {
    if (args.length == 2) {
      send(feedback, false, "notification.chat.current", CbbgConfig.get().notifyChat());
      send(feedback, false, "notification.chat.usage");
      if (toast) {
        send(feedback, false, "notification.toast.current", CbbgConfig.get().notifyToast());
        send(feedback, false, "notification.toast.usage");
      }
      return;
    }
    String kind = args[2];
    if (!kind.equals("chat") && !(kind.equals(TOAST) && toast)) {
      usage(feedback);
      return;
    }
    if (args.length == 4 && (args[3].equals("true") || args[3].equals("false"))) {
      boolean value = Boolean.parseBoolean(args[3]);
      if (kind.equals("chat")) CbbgConfig.setNotifyChat(value);
      else CbbgConfig.setNotifyToast(value);
    } else if (args.length != 3) {
      send(feedback, true, NOTIFICATION_PREFIX + kind + USAGE_SUFFIX);
      return;
    }
    send(
        feedback,
        false,
        NOTIFICATION_PREFIX + kind + CURRENT_SUFFIX,
        kind.equals("chat") ? CbbgConfig.get().notifyChat() : CbbgConfig.get().notifyToast());
    if (args.length == 3) send(feedback, false, NOTIFICATION_PREFIX + kind + USAGE_SUFFIX);
  }

  private static void usage(Feedback feedback) {
    feedback.send(true, "commands.generic.usage", "/cbbg help");
  }

  private static void send(Feedback feedback, boolean error, String key, Object... args) {
    feedback.send(error, "cbbg.command." + key, args);
  }
}
