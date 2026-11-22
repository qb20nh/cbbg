package com.qb20nh.cbbg.parity;

import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.locale.Language;

/** Reloads each packaged locale and opens its actual settings screen through Mod Menu. */
final class ClassicLocalesSmoke {
  private final String originalLocale;
  private final Map<String, Map<String, String>> locales = new TreeMap<>();
  private final Iterator<Map.Entry<String, Map<String, String>>> remaining;
  private Map.Entry<String, Map<String, String>> current;
  private CompletableFuture<Void> reload;
  private Screen settings;
  private long openedAt;

  ClassicLocalesSmoke(Minecraft client) throws Exception {
    originalLocale = client.getLanguageManager().getSelected();
    var resources =
        client
            .getResourceManager()
            .listResources(
                "lang", id -> id.getNamespace().equals("cbbg") && id.getPath().endsWith(".json"));
    for (var entry : resources.entrySet()) {
      String path = entry.getKey().getPath();
      String locale = path.substring(5, path.length() - 5);
      Map<String, String> translations = new TreeMap<>();
      try (var stream = entry.getValue().open()) {
        Language.loadFromJson(stream, translations::put);
      }
      if (translations.isEmpty() || client.getLanguageManager().getLanguage(locale) == null) {
        throw new AssertionError("Empty or unavailable packaged CBBG locale: " + locale);
      }
      locales.put(locale, translations);
    }
    if (locales.size() < 2 || !locales.containsKey("en_us")) {
      throw new AssertionError("Missing packaged CBBG locales");
    }
    for (var entry : locales.entrySet()) {
      if (!entry.getValue().keySet().equals(locales.get("en_us").keySet())) {
        throw new AssertionError("Translation keys differ from en_us: " + entry.getKey());
      }
    }
    remaining = locales.entrySet().iterator();
    next(client);
  }

  boolean tick(Minecraft client) throws Exception {
    if (!reload.isDone() || client.getOverlay() != null) return false;
    reload.join();
    if (current == null) {
      if (!originalLocale.equals(client.getLanguageManager().getSelected())
          || client.screen != null) {
        throw new AssertionError("Previous language or world screen was not restored");
      }
      System.out.println(
          "CBBG_CLASSIC_LOCALES_PASS restored=" + originalLocale + " count=" + locales.size());
      return true;
    }
    String locale = current.getKey();
    if (settings == null) {
      if (!locale.equals(client.getLanguageManager().getSelected())) {
        throw new AssertionError("Minecraft did not select " + locale);
      }
      for (var entry : current.getValue().entrySet()) {
        if (!entry.getValue().equals(Language.getInstance().getOrDefault(entry.getKey()))) {
          throw new AssertionError(
              "Incorrect active translation: " + locale + "/" + entry.getKey());
        }
      }
      settings = ClassicVulkanSettings.open(client);
      openedAt = ClassicPackagedSmoke.presentationCount();
      return false;
    }
    if (ClassicPackagedSmoke.presentationCount() <= openedAt) return false;
    if (client.screen != settings)
      throw new AssertionError("Localized settings screen disappeared");
    ClassicVulkanSettings.screenshot(client, "locale-" + locale + ".png");
    ClassicVulkanSettings.click(settings, "cbbg.config.button.done");
    if (client.screen != null)
      throw new AssertionError("Localized Done button did not close settings");
    System.out.println("CBBG_CLASSIC_LOCALE code=" + locale + " keys=" + current.getValue().size());
    settings = null;
    next(client);
    return false;
  }

  private void next(Minecraft client) {
    current = remaining.hasNext() ? remaining.next() : null;
    String requested = current == null ? originalLocale : current.getKey();
    if (client.getLanguageManager().getSelected().equals(requested)) {
      reload = CompletableFuture.completedFuture(null);
    } else {
      client.getLanguageManager().setSelected(requested);
      reload = client.reloadResourcePacks();
    }
  }
}
