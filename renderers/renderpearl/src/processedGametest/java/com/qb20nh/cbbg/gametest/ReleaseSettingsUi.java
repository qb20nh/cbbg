package com.qb20nh.cbbg.gametest;

import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;

/** Finds the packaged settings UI by its public, localized labels. */
record ReleaseSettingsUi(
    CycleButton<?> mode,
    CycleButton<?> format,
    AbstractSliderButton strength,
    AbstractSliderButton size,
    AbstractSliderButton depth,
    EditBox seed,
    Button generate,
    CycleButton<?> chat,
    CycleButton<?> toast,
    Button done) {
  static Screen mods(ClientGameTestContext context, Screen parent) {
    return Installed.mods(context, parent);
  }

  static void open(ClientGameTestContext context, Screen mods) {
    Installed.open(context, mods);
  }

  static void setStrength(ClientGameTestContext context, float strength) {
    context.runOnClient(
        client -> slide(from(client.gui.screen()).strength(), (strength - 0.5) / 3.5));
    if (ReleaseClient.settings().get("strength").getAsFloat() != strength) {
      throw new AssertionError("UI did not save strength " + strength);
    }
  }

  private static final class Installed {
    static Screen mods(ClientGameTestContext context, Screen parent) {
      return context.computeOnClient(
          client -> new com.terraformersmc.modmenu.gui.ModsScreen(parent));
    }

    static void open(ClientGameTestContext context, Screen screen) {
      context.setScreen(() -> screen);
      context.runOnClient(
          client -> {
            var mods = (com.terraformersmc.modmenu.gui.ModsScreen) screen;
            if (!com.terraformersmc.modmenu.ModMenu.hasConfigScreen("cbbg")
                || !mods.getModHasConfigScreen("cbbg")) {
              throw new AssertionError("CBBG Mod Menu entry point was not discovered");
            }
            mods.safelyOpenConfigScreen("cbbg");
          });
      context.waitFor(client -> isSettings(client.gui.screen()), 600);
      context.waitTick();
    }
  }

  static boolean isSettings(Screen screen) {
    return screen != null
        && screen
            .getTitle()
            .getString()
            .equals(Component.translatable("cbbg.config.title").getString());
  }

  static ReleaseSettingsUi from(Screen screen) {
    if (!isSettings(screen)) throw new AssertionError("Expected packaged CBBG settings screen");
    return new ReleaseSettingsUi(
        find(screen, CycleButton.class, "cbbg.config.mode"),
        find(screen, CycleButton.class, "cbbg.config.format"),
        find(screen, AbstractSliderButton.class, "cbbg.config.strength.label"),
        find(screen, AbstractSliderButton.class, "cbbg.config.size.label"),
        find(screen, AbstractSliderButton.class, "cbbg.config.depth.label"),
        find(screen, EditBox.class, "cbbg.config.seed.label"),
        find(screen, Button.class, "cbbg.config.button.generate_stbn"),
        find(screen, CycleButton.class, "cbbg.config.notify.chat"),
        find(screen, CycleButton.class, "cbbg.config.notify.toast"),
        find(screen, Button.class, "cbbg.config.button.done"));
  }

  private static <T extends AbstractWidget> T find(Screen screen, Class<T> type, String key) {
    String label = Component.translatable(key).getString();
    var matches =
        screen.children().stream()
            .filter(type::isInstance)
            .map(type::cast)
            .filter(widget -> widget.getMessage().getString().contains(label))
            .toList();
    if (matches.size() != 1) throw new AssertionError("Expected one public widget for " + key);
    return matches.getFirst();
  }

  static void click(AbstractWidget widget) {
    clickAt(widget, widget.getX() + widget.getWidth() / 2.0);
  }

  static void slide(AbstractSliderButton widget, double fraction) {
    clickAt(widget, widget.getX() + 4 + fraction * (widget.getWidth() - 8));
  }

  private static void clickAt(AbstractWidget widget, double x) {
    widget.onClick(
        new MouseButtonEvent(
            x, widget.getY() + widget.getHeight() / 2.0, new MouseButtonInfo(0, 0)),
        false);
  }

  List<AbstractWidget> lockedControls() {
    return List.of(format, strength, size, depth, seed, generate, chat, toast);
  }
}
