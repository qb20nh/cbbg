package com.qb20nh.cbbg.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** Finds the packaged settings UI by its public, localized labels. */
@NullMarked
final class ReleaseSettingsUi {
  private ReleaseSettingsUi() {}

  static Screen mods(ClientGameTestContext context, @Nullable Screen parent) {
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
    static Screen mods(ClientGameTestContext context, @Nullable Screen parent) {
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

  static boolean isSettings(@Nullable Screen screen) {
    return screen != null
        && screen
            .getTitle()
            .getString()
            .equals(Component.translatable("cbbg.config.title").getString());
  }

  static ReleaseSettingsGuiGameTest.Widgets from(@Nullable Screen screen) {
    return ReleaseSettingsGuiGameTest.Widgets.from(screen);
  }

  static void click(AbstractWidget widget) {
    clickAt(widget, widget.getX() + widget.getWidth() / 2.0);
  }

  static void slide(AbstractSliderButton widget, double fraction) {
    clickAt(widget, widget.getX() + 4 + fraction * (widget.getWidth() - 8));
  }

  private static void clickAt(AbstractWidget widget, double x) {
    ReleaseGuiInput.click(x, widget.getY() + widget.getHeight() / 2.0);
  }
}
