package com.qb20nh.cbbg.parity;

import com.qb20nh.cbbg.parity.mixin.ClassicChatObservation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.util.FormattedCharSequence;

public final class ClassicVulkanSettings {
  private static final String GENERATE = "cbbg.config.button.generate_stbn";
  private static final String CONFIRM = "cbbg.config.confirm.regenerate_stbn.title";
  private static final String TOAST_TITLE = "cbbg.toast.stbn.title";
  private static final boolean CHECK_SETTINGS = Boolean.getBoolean("cbbg.test.vulkanSettings");
  private static boolean startToast;
  private static boolean completeToast;

  private enum Phase {
    OPEN,
    CONFIRM_CANCEL,
    CANCELLED,
    CONFIRM_GENERATE,
    GENERATING,
    RETURNED
  }

  private final Screen settings;
  private final Path manifest;
  private final String warmManifest;
  private Phase phase = Phase.OPEN;
  private long nextPresent;

  ClassicVulkanSettings(Minecraft client) throws Exception {
    settings =
        (Screen)
            Class.forName("com.terraformersmc.modmenu.ModMenu")
                .getMethod("getConfigScreen", String.class, Screen.class)
                .invoke(null, "cbbg", null);
    if (settings == null || !titleIs(settings, "cbbg.config.title")) {
      throw new AssertionError("Mod Menu did not open the packaged CBBG settings");
    }
    manifest = client.gameDirectory.toPath().resolve(".cbbg/stbn_64x64x8.sha256");
    warmManifest = Files.readString(manifest);
    client.setScreen(settings);
  }

  public static void observeToast(Component title, List<FormattedCharSequence> lines, long time) {
    if (!CHECK_SETTINGS
        || !title.getString().equals(Component.translatable(TOAST_TITLE).getString())) return;
    StringBuilder message = new StringBuilder();
    for (var line : lines) {
      if (!message.isEmpty()) message.append(' ');
      line.accept(
          (index, style, codePoint) -> {
            message.appendCodePoint(codePoint);
            return true;
          });
    }
    String text = message.toString();
    if (!startToast
        && text.equals(Component.translatable("cbbg.toast.stbn.generating").getString())) {
      startToast = true;
      System.out.println("CBBG_CLASSIC_VULKAN_TOAST rendered=generating");
    }
    // ToastComponent reports zero visible time while the toast is sliding onto the screen.
    if (!completeToast
        && time > 0
        && text.equals(Component.translatable("cbbg.toast.stbn.complete").getString())) {
      completeToast = true;
      System.out.println("CBBG_CLASSIC_VULKAN_TOAST rendered=complete");
    }
  }

  boolean tick(Minecraft client, long presents) throws Exception {
    if (presents < nextPresent) return false;
    switch (phase) {
      case OPEN -> {
        requireScreen(client, settings);
        screenshot(client, "vulkan-settings.png");
        click(settings, GENERATE);
        phase = Phase.CONFIRM_CANCEL;
      }
      case CONFIRM_CANCEL -> {
        Screen confirm = requireConfirmation(client);
        screenshot(client, "vulkan-confirmation.png");
        click(confirm, "gui.no");
        phase = Phase.CANCELLED;
      }
      case CANCELLED -> {
        requireScreen(client, settings);
        if (startToast
            || completeToast
            || chatCount(client, "generating", ChatFormatting.YELLOW) != 0
            || !Files.readString(manifest).equals(warmManifest)) {
          throw new AssertionError("Cancelling regeneration changed the warm Vulkan noise cache");
        }
        System.out.println("CBBG_CLASSIC_VULKAN_CONFIRMATION cancelled=unchanged");
        click(settings, GENERATE);
        phase = Phase.CONFIRM_GENERATE;
      }
      case CONFIRM_GENERATE -> {
        click(requireConfirmation(client), "gui.yes");
        requireScreen(client, settings);
        if (Files.exists(manifest)) {
          throw new AssertionError("Confirmed regeneration did not invalidate the warm cache");
        }
        phase = Phase.GENERATING;
      }
      case GENERATING -> {
        if (!completeToast) return false;
        if (!startToast
            || chatCount(client, "generating", ChatFormatting.YELLOW) != 1
            || chatCount(client, "complete", ChatFormatting.GREEN) != 1) {
          throw new AssertionError(
              "Expected one of each styled chat notification and both rendered toasts");
        }
        if (!Files.readString(manifest).equals(warmManifest)
            || ClassicVulkanSmoke.noiseWidth != 64) {
          throw new AssertionError(
              "Regeneration changed deterministic noise or failed to upload it");
        }
        screenshot(client, "vulkan-generation-complete.png");
        click(settings, "cbbg.config.button.done");
        phase = Phase.RETURNED;
      }
      case RETURNED -> {
        requireScreen(client, null);
        ClassicVulkanSmoke.checkSceneFormat(97);
        ClassicVulkanSmoke.capture(client, "vulkan-world.png", true);
        System.out.println("CBBG_CLASSIC_VULKAN_SETTINGS confirmations=2 chat=2 toasts=2");
        return true;
      }
    }
    nextPresent = presents + 12;
    return false;
  }

  private static void requireScreen(Minecraft client, Screen expected) {
    if (client.screen != expected) throw new AssertionError("Unexpected screen after click");
  }

  private static Screen requireConfirmation(Minecraft client) {
    if (client.screen instanceof ConfirmScreen confirm && titleIs(confirm, CONFIRM)) return confirm;
    throw new AssertionError("CBBG did not show its regeneration confirmation");
  }

  private static boolean titleIs(Screen screen, String key) {
    return screen.getTitle().getString().equals(Component.translatable(key).getString());
  }

  private static void click(Screen screen, String translation) {
    String label = Component.translatable(translation).getString();
    var widget =
        screen.children().stream()
            .filter(AbstractWidget.class::isInstance)
            .map(AbstractWidget.class::cast)
            .filter(candidate -> candidate.getMessage().getString().equals(label))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No control with label " + label));
    if (!screen.mouseClicked(
        widget.getX() + widget.getWidth() / 2.0, widget.getY() + widget.getHeight() / 2.0, 0)) {
      throw new AssertionError("Control did not accept mouse input: " + label);
    }
  }

  private static long chatCount(Minecraft client, String state, ChatFormatting formatting) {
    var chat = (ClassicChatObservation) client.gui.getChat();
    String expected = Component.translatable("cbbg.chat.stbn." + state).getString();
    return chat.cbbg$messages().stream()
        .map(entry -> entry.content())
        .filter(message -> message.getString().equals(expected))
        .filter(
            message -> TextColor.fromLegacyFormat(formatting).equals(message.getStyle().getColor()))
        .count();
  }

  private static void screenshot(Minecraft client, String file) throws Exception {
    try (var image = Screenshot.takeScreenshot(client.getMainRenderTarget())) {
      if (image.getWidth() != client.getWindow().getWidth()
          || image.getHeight() != client.getWindow().getHeight()) {
        throw new AssertionError("Settings screenshot does not match the window size");
      }
      image.writeToFile(client.gameDirectory.toPath().resolve(file));
    }
  }
}
