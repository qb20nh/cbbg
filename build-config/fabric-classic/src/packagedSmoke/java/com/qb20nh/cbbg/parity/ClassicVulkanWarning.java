package com.qb20nh.cbbg.parity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

/** Checks the warning drawn by the packaged settings screen on the real fallback path. */
public final class ClassicVulkanWarning {
  private static final String KEY = "cbbg.config.status.no_float_formats";
  private static final boolean CHECK = Boolean.getBoolean("cbbg.test.vulkanFormatWarning");
  private static int warningDraws;
  private final int format = Integer.getInteger("cbbg.test.vulkanSceneFormat");
  private final Screen screen;
  private long nextPresent;
  private boolean captured;

  ClassicVulkanWarning(Minecraft client, long presentations) throws ReflectiveOperationException {
    ClassicVulkanSmoke.checkSceneFormat(format);
    screen = ClassicVulkanSettings.open(client);
    nextPresent = presentations + 12;
  }

  public static void observe(Font font, Component text, int x, int y, int color) {
    if (!CHECK
        || !(text.getContents() instanceof TranslatableContents translated)
        || !KEY.equals(translated.getKey())) return;
    if (color != 0xFFFFAA00 || text.getString().equals(KEY)) {
      throw new AssertionError("Vulkan fallback warning is missing its localized text or color");
    }
    if (warningDraws++ == 0) {
      System.out.println(
          "CBBG_CLASSIC_VULKAN_FORMAT_LABEL x="
              + x
              + " y="
              + y
              + " width="
              + font.width(text)
              + " height="
              + font.lineHeight
              + " text="
              + text.getString());
    }
  }

  boolean tick(Minecraft client, long presentations) throws Exception {
    if (presentations < nextPresent) return false;
    if (!captured) {
      if (client.screen != screen || (warningDraws > 0) != (format == 37)) {
        throw new AssertionError("Unexpected fallback warning at Vulkan scene format " + format);
      }
      ClassicVulkanSettings.screenshot(client, "vulkan-format-settings.png");
      screen.onClose();
      captured = true;
      nextPresent = presentations + 12;
      return false;
    }
    ClassicVulkanSmoke.checkSceneFormat(format);
    ClassicVulkanSmoke.capture(client, "vulkan-world.png", true);
    System.out.println(
        "CBBG_CLASSIC_VULKAN_FORMAT_WARNING format=" + format + " draws=" + warningDraws);
    return true;
  }
}
