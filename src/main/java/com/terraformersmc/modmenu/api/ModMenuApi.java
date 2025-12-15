package com.terraformersmc.modmenu.api;

import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Minimal API surface used by cbbg. See Mod Menu docs: https://github.com/TerraformersMC/ModMenu */
public interface ModMenuApi {
  static Screen createModsScreen(Screen previous) {
    throw new UnsupportedOperationException("Provided by Mod Menu at runtime.");
  }

  static Component createModsButtonText() {
    throw new UnsupportedOperationException("Provided by Mod Menu at runtime.");
  }

  default ConfigScreenFactory<?> getModConfigScreenFactory() {
    return null;
  }

  default UpdateChecker getUpdateChecker() {
    return null;
  }

  default Map<String, ConfigScreenFactory<?>> getProvidedConfigScreenFactories() {
    return Map.of();
  }

  default Map<String, UpdateChecker> getProvidedUpdateCheckers() {
    return Map.of();
  }

  default void attachModpackBadges(Consumer<String> consumer) {}
}
