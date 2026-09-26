package com.qb20nh.cbbg.gametest;

import com.google.gson.JsonObject;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.locale.Language;

/** Tests the optimized artifact's settings through its real Mod Menu entry point. */
public final class ReleaseSettingsGameTest implements FabricClientGameTest {
  @Override
  public void runTest(ClientGameTestContext context) {
    boolean expected =
        List.of(System.getProperty("cbbg.test.compat", "none").split("\\+")).contains("modmenu");
    boolean installed = FabricLoader.getInstance().isModLoaded("modmenu");
    check(installed == expected, "Mod Menu presence does not match requested fixture");
    if (installed) {
      String version =
          FabricLoader.getInstance()
              .getModContainer("modmenu")
              .orElseThrow()
              .getMetadata()
              .getVersion()
              .getFriendlyString();
      check(
          version.equals(System.getProperty("cbbg.test.modmenu.version")),
          "Unexpected Mod Menu version: " + version);
      Installed.run(context);
    }
  }

  // Optional API types are resolved only in fixtures that install Mod Menu.
  private static final class Installed {
    static void run(ClientGameTestContext context) {
      JsonObject original = ReleaseClient.settings().deepCopy();
      try (var world = context.worldBuilder().create()) {
        world.getConnection().waitForChunksRender();
        var parent = context.computeOnClient(client -> client.gui.screen());
        var mods =
            context.computeOnClient(
                client -> new com.terraformersmc.modmenu.gui.ModsScreen(parent));
        try {
          command(context, "mode set disabled");
          command(context, "format set rgba16f");
          command(context, "stbn size 16");
          command(context, "stbn depth 8");
          command(context, "stbn seed 0");
          command(context, "notification chat true");
          command(context, "notification toast true");
          command(context, "mode set enabled");
          ReleaseClient.awaitFormat(context, GpuFormat.RGBA16_FLOAT);
          ReleaseClient.awaitCache(context, 16, 8, 0);
          ReleaseClient.awaitDrawAfter(context, ProcessedRenderObservations.draws());

          open(context, mods);
          context.runOnClient(
              client -> {
                var ui = ReleaseSettingsUi.from(client.gui.screen());
                ReleaseSettingsUi.click(ui.format());
                ReleaseSettingsUi.slide(ui.strength(), 1);
              });
          ReleaseClient.awaitFormat(context, GpuFormat.RGBA32_FLOAT);
          ReleaseClient.awaitDrawAfter(context, ProcessedRenderObservations.draws());
          // Set pending dimensions without changing the existing GPU noise.
          command(context, "stbn size 32");
          command(context, "stbn depth 16");
          open(context, mods);
          GpuTextureView oldNoise =
              context.computeOnClient(client -> ProcessedRenderObservations.lastDitherNoise());
          check(oldNoise != null && !oldNoise.texture().isClosed(), "No active UI fixture noise");
          context.runOnClient(client -> editNoise(client.gui.screen()));
          retainNoise(context, oldNoise, "editing settings");
          context.clickScreenButton("cbbg.config.button.generate_stbn");
          context.waitForScreen(ConfirmScreen.class);
          ReleaseClient.screenshot(context, "release-settings-confirm");
          context.clickScreenButton("gui.no");
          waitSettings(context);
          retainNoise(context, oldNoise, "canceling generation");
          context.clickScreenButton("cbbg.config.button.generate_stbn");
          context.waitForScreen(ConfirmScreen.class);
          context.clickScreenButton("gui.yes");
          waitSettings(context);
          ReleaseClient.awaitCache(context, 16, 8, 123);
          long before = ProcessedRenderObservations.draws();
          context.waitFor(
              client -> {
                var noise = ProcessedRenderObservations.lastDitherNoise();
                return noise != null
                    && noise != oldNoise
                    && !noise.texture().isClosed()
                    && noise.getWidth(0) == 16
                    && noise.getHeight(0) == 16
                    && ProcessedRenderObservations.draws() > before;
              },
              600);
          check(oldNoise.texture().isClosed(), "Confirmed generation retained old GPU noise");
          context.runOnClient(
              client -> {
                var ui = ReleaseSettingsUi.from(client.gui.screen());
                check(ui.chat().active && ui.toast().active, "Notifications were locked");
                ReleaseSettingsUi.click(ui.chat());
                ReleaseSettingsUi.click(ui.toast());
                ReleaseSettingsUi.click(ui.mode());
              });
          ReleaseClient.assertSettings("DISABLED", "RGBA32F", 16, 8, 123);
          JsonObject edited = ReleaseClient.settings();
          check(
              edited.get("strength").getAsFloat() == 4f
                  && !edited.get("notifyChat").getAsBoolean()
                  && !edited.get("notifyToast").getAsBoolean(),
              "Widget edits were not saved");
          open(context, mods);
          context.runOnClient(
              client -> {
                var ui = ReleaseSettingsUi.from(client.gui.screen());
                check(ui.mode().active && ui.done().active, "Mode and Done were locked");
                for (var widget : ui.lockedControls()) {
                  check(!widget.active, "Disabled mode left an editable control");
                }
                ui.seed().setValue("456");
                check(
                    ReleaseClient.settings().get("stbnSeed").getAsLong() == 123,
                    "Locked seed callback changed saved settings");
              });
          ReleaseClient.screenshot(context, "release-settings-disabled");
          context.clickScreenButton("cbbg.config.button.done");
          context.runOnClient(
              client -> {
                check(
                    client.gui.screen() == mods && mods.modScreenErrors.isEmpty(),
                    "Done did not return cleanly to Mod Menu");
                mods.onClose();
                check(client.gui.screen() == parent, "Mod Menu lost its parent screen");
              });
          locales(context, mods);
        } finally {
          restore(context, mods, original);
          context.setScreen(() -> parent);
        }
      }
    }

    private static void open(
        ClientGameTestContext context, com.terraformersmc.modmenu.gui.ModsScreen mods) {
      ReleaseSettingsUi.open(context, mods);
    }

    private static void restore(
        ClientGameTestContext context,
        com.terraformersmc.modmenu.gui.ModsScreen mods,
        JsonObject original) {
      command(context, "mode set enabled");
      open(context, mods);
      ReleaseSettingsUi.setStrength(context, original.get("strength").getAsFloat());
      command(context, "mode set disabled");
      command(
          context,
          "format set " + original.get("pixelFormat").getAsString().toLowerCase(Locale.ROOT));
      command(context, "stbn size " + original.get("stbnSize").getAsInt());
      command(context, "stbn depth " + original.get("stbnDepth").getAsInt());
      command(context, "stbn seed " + original.get("stbnSeed").getAsLong());
      command(context, "notification chat " + original.get("notifyChat").getAsBoolean());
      command(context, "notification toast " + original.get("notifyToast").getAsBoolean());
      command(context, "mode set " + original.get("mode").getAsString().toLowerCase(Locale.ROOT));
      context.waitFor(client -> original.equals(ReleaseClient.settings()), 600);
    }

    private static void locales(
        ClientGameTestContext context, com.terraformersmc.modmenu.gui.ModsScreen mods) {
      String original =
          context.computeOnClient(client -> client.getLanguageManager().getSelected());
      Map<String, Map<String, String>> locales =
          context.computeOnClient(
              client -> {
                Map<String, Map<String, String>> result = new TreeMap<>();
                client
                    .getResourceManager()
                    .listResources(
                        "lang",
                        id -> id.getNamespace().equals("cbbg") && id.getPath().endsWith(".json"))
                    .forEach(
                        (id, resource) -> {
                          String locale = id.getPath().substring(5, id.getPath().length() - 5);
                          Map<String, String> expected = new TreeMap<>();
                          try (var stream = resource.open()) {
                            Language.loadFromJson(stream, expected::put);
                          } catch (IOException failure) {
                            throw new AssertionError(
                                "Could not read packaged locale " + locale, failure);
                          }
                          check(
                              !expected.isEmpty()
                                  && client.getLanguageManager().getLanguage(locale) != null,
                              "Empty or unavailable locale " + locale);
                          result.put(locale, expected);
                        });
                check(
                    result.containsKey("en_us") && result.size() > 1,
                    "Packaged translations missing");
                return result;
              });
      try {
        for (var entry : locales.entrySet()) {
          check(
              entry.getValue().keySet().equals(locales.get("en_us").keySet()),
              "Locale keys differ from English: " + entry.getKey());
          reload(context, entry.getKey());
          context.runOnClient(
              client ->
                  entry
                      .getValue()
                      .forEach(
                          (key, value) ->
                              check(
                                  value.equals(Language.getInstance().getOrDefault(key)),
                                  "Incorrect runtime translation: " + entry.getKey() + "/" + key)));
          open(context, mods);
          context.runOnClient(client -> ReleaseSettingsUi.from(client.gui.screen()));
          ReleaseClient.screenshot(context, "release-locale-" + entry.getKey());
        }
      } finally {
        reload(context, original);
      }
    }

    private static void reload(ClientGameTestContext context, String locale) {
      var future =
          context.computeOnClient(
              client -> {
                client.getLanguageManager().setSelected(locale);
                return client.reloadResourcePacks();
              });
      context.waitFor(client -> future.isDone(), 600);
      future.join();
      context.waitFor(client -> client.gui.overlay() == null, 600);
    }
  }

  private static void editNoise(Screen screen) {
    var ui = ReleaseSettingsUi.from(screen);
    ReleaseSettingsUi.slide(ui.size(), 0);
    ReleaseSettingsUi.slide(ui.depth(), 0);
    ui.seed().setValue("123");
    ui.seed().setValue("12x");
    check(ui.seed().getValue().equals("123"), "Invalid seed text accepted");
    ui.seed().setCursorPosition(1);
    ui.seed().setHighlightPos(2);
    ui.seed().insertText("-");
    check(ui.seed().getValue().equals("123"), "Invalid selected seed insertion accepted");
    ui.seed().insertText("9");
    check(ui.seed().getValue().equals("193"), "Seed selection replacement failed");
    ui.seed().setValue("-42");
    check(ReleaseClient.settings().get("stbnSeed").getAsLong() == -42, "Negative seed not saved");
    ui.seed().moveCursorToEnd(false);
    ui.seed().setHighlightPos(0);
    ui.seed().insertText("123");
    check(ui.seed().getValue().equals("123"), "Reverse seed selection failed");
    ui.seed().setMaxLength(3);
    ui.seed().moveCursorToEnd(false);
    ui.seed().insertText("4");
    check(ui.seed().getValue().equals("123"), "Seed length limit failed");
    ui.seed().setMaxLength(32);
    for (String invalid : List.of("-", "9223372036854775808", "-9223372036854775809")) {
      ui.seed().setValue(invalid);
      check(ui.seed().getValue().equals(invalid), "Intermediate seed text rejected");
      ReleaseSettingsUi.click(ui.generate());
      check(
          ReleaseClient.settings().get("stbnSeed").getAsLong() == 123,
          "Invalid seed changed saved value");
      // A valid click navigates away; this screen must remain the current public screen.
      check(
          net.minecraft.client.Minecraft.getInstance().gui.screen() == screen,
          "Invalid seed opened generation confirmation");
    }
    ui.seed().setValue("123");
  }

  private static void retainNoise(
      ClientGameTestContext context, GpuTextureView noise, String action) {
    long before = ProcessedRenderObservations.draws();
    ReleaseClient.awaitDrawAfter(context, before);
    context.waitTicks(5);
    context.runOnClient(
        client ->
            check(
                ProcessedRenderObservations.lastDitherNoise() == noise
                    && !noise.texture().isClosed(),
                "GPU noise changed while " + action));
  }

  private static void waitSettings(ClientGameTestContext context) {
    context.waitFor(client -> ReleaseSettingsUi.isSettings(client.gui.screen()), 600);
    context.waitTick();
  }

  private static void command(ClientGameTestContext context, String command) {
    ReleaseClient.command(context, command);
  }

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
