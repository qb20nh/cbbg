package com.qb20nh.cbbg;

import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.config.CbbgConfig.Mode;
import com.qb20nh.cbbg.config.CbbgConfig.PixelFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Client-only command grammar for games predating Brigadier. */
public final class LegacyCommandGrammar {
  private LegacyCommandGrammar() {}

  public interface Feedback {
    Object translate(String key);

    void send(boolean error, String key, Object... args);
  }

  /** Null leaves completion to vanilla; an empty list claims an invalid CBBG argument. */
  public static List<String> suggest(String message, boolean toast) {
    String[] args = message.split("\\s+", -1);
    if (!args[0].equals("/cbbg")) return null;
    List<String> candidates = new ArrayList<>();
    if (args.length == 1) candidates.add("/cbbg");
    else if (args.length == 2) {
      java.util.Collections.addAll(candidates, "help", "mode", "format", "stbn", "notification");
    } else if (args.length == 3) {
      if (args[1].equals("mode") || args[1].equals("format")) candidates.add("set");
      else if (args[1].equals("stbn")) {
        java.util.Collections.addAll(candidates, "generate", "size", "depth", "seed", "reset");
      } else if (args[1].equals("notification")) {
        candidates.add("chat");
        if (toast) candidates.add("toast");
      }
    } else if (args.length == 4) {
      if (args[1].equals("mode") && args[2].equals("set")) {
        for (Mode mode : Mode.values()) candidates.add(mode.getSerializedName());
      } else if (args[1].equals("format") && args[2].equals("set")) {
        for (PixelFormat format : PixelFormat.values()) {
          if (format != PixelFormat.RGBA8) candidates.add(format.getSerializedName());
        }
      } else if (args[1].equals("notification")
          && (args[2].equals("chat") || args[2].equals("toast") && toast)) {
        java.util.Collections.addAll(candidates, "true", "false");
      }
    }
    String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
    candidates.removeIf(candidate -> !candidate.startsWith(prefix));
    return candidates;
  }

  public static boolean execute(
      String message, boolean toast, Feedback feedback, Runnable regenerate, Runnable reload) {
    String[] args = message.trim().split("\\s+");
    if (!args[0].equals("/cbbg")) return false;
    if (args.length == 1 || args.length == 2 && args[1].equals("help")) {
      for (String key :
          new String[] {
            "header", "mode", "mode_set", "format", "format_set", "stbn", "notification"
          }) {
        send(feedback, false, "root.help." + key);
      }
    } else if (args[1].equals("mode") || args[1].equals("format")) {
      selection(args, feedback);
    } else if (args[1].equals("stbn")) {
      noise(args, feedback, regenerate, reload);
    } else if (args[1].equals("notification")) {
      notification(args, toast, feedback);
    } else {
      usage(feedback);
    }
    return true;
  }

  private static void selection(String[] args, Feedback feedback) {
    String kind = args[1];
    if (args.length == 2) {
      send(feedback, false, kind + ".current", name(kind, feedback));
      return;
    }
    if (args.length != 4 || !args[2].equals("set")) {
      send(feedback, true, kind + ".usage");
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
      for (String key : new String[] {"generate", "size", "depth", "seed", "reset"}) {
        send(feedback, false, "stbn.usage." + key);
      }
      return;
    }
    String kind = args[2];
    if (args.length == 3 && kind.equals("generate")) {
      send(feedback, false, "stbn.generating");
      regenerate.run();
    } else if (args.length == 3 && kind.equals("reset")) {
      CbbgConfig.setStbnSize(128);
      CbbgConfig.setStbnDepth(64);
      CbbgConfig.setStbnSeed(0);
      reload.run();
      send(feedback, false, "stbn.reset");
    } else if (kind.equals("size") || kind.equals("depth") || kind.equals("seed")) {
      if (args.length == 3) {
        CbbgConfig config = CbbgConfig.get();
        long value =
            kind.equals("size")
                ? config.stbnSize()
                : kind.equals("depth") ? config.stbnDepth() : config.stbnSeed();
        send(feedback, false, "stbn." + kind + ".current", value);
        send(feedback, false, "stbn." + kind + ".usage");
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
        send(feedback, true, "stbn." + kind + ".usage");
        return;
      }
      if (kind.equals("seed")) CbbgConfig.setStbnSeed(value);
      else {
        int minimum = kind.equals("size") ? 16 : 8;
        int maximum = kind.equals("size") ? 256 : 128;
        if (value < minimum || value > maximum || Long.bitCount(value) != 1) {
          send(feedback, true, "stbn." + kind + ".invalid_pow2");
          send(feedback, true, "stbn." + kind + ".usage");
          return;
        }
        if (kind.equals("size")) CbbgConfig.setStbnSize((int) value);
        else CbbgConfig.setStbnDepth((int) value);
      }
      send(feedback, false, "stbn." + kind + ".set", value);
    } else usage(feedback);
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
    if (!kind.equals("chat") && !(kind.equals("toast") && toast)) {
      usage(feedback);
      return;
    }
    if (args.length == 4 && (args[3].equals("true") || args[3].equals("false"))) {
      boolean value = Boolean.parseBoolean(args[3]);
      if (kind.equals("chat")) CbbgConfig.setNotifyChat(value);
      else CbbgConfig.setNotifyToast(value);
    } else if (args.length != 3) {
      send(feedback, true, "notification." + kind + ".usage");
      return;
    }
    send(
        feedback,
        false,
        "notification." + kind + ".current",
        kind.equals("chat") ? CbbgConfig.get().notifyChat() : CbbgConfig.get().notifyToast());
    if (args.length == 3) send(feedback, false, "notification." + kind + ".usage");
  }

  private static void usage(Feedback feedback) {
    feedback.send(true, "commands.generic.usage", "/cbbg help");
  }

  private static void send(Feedback feedback, boolean error, String key, Object... args) {
    feedback.send(error, "cbbg.command." + key, args);
  }
}
