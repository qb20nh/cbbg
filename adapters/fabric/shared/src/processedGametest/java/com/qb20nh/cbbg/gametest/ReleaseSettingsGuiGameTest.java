package com.qb20nh.cbbg.gametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
public final class ReleaseSettingsGuiGameTest implements FabricClientGameTest {
  private static final int WAIT_TICKS = 600;
  private static final String SCREEN_CLASS = "com.qb20nh.cbbg.config.gui.CbbgConfigScreen";
  private static final String GENERATION_STARTED = "Starting Async STBN Math Generation (16x16x8)";
  private static final String CACHE_HIT = "Valid STBN cache found for 16x16x8.";
  private static final String[] LABELS = {
    "cbbg.config.mode",
    "cbbg.config.format",
    "cbbg.config.strength.label",
    "cbbg.config.size.label",
    "cbbg.config.depth.label",
    "cbbg.config.seed.label",
    "cbbg.config.button.generate_stbn",
    "cbbg.config.notify.chat",
    "cbbg.config.notify.toast",
    "cbbg.config.button.done"
  };
  private static final String[] TRANSLATIONS = {
    "cbbg.config.title",
    "cbbg.mode.enabled",
    "cbbg.mode.disabled",
    "cbbg.mode.demo",
    "cbbg.config.confirm.regenerate_stbn.title",
    "cbbg.config.confirm.regenerate_stbn.message"
  };

  @Override
  public void runTest(ClientGameTestContext context) {
    ReleaseGraphics.check(context);
    JsonObject original = settings();
    String originalLocale =
        context.computeOnClient(client -> client.getLanguageManager().getSelected());
    @Nullable Screen originalParent = context.computeOnClient(ReleaseViewport::currentScreen);
    try (var world = context.worldBuilder().create()) {
      ReleaseViewport.waitForChunks(world);
      try {
        Set<String> locales = availableLocales(context);
        for (String locale : new java.util.TreeSet<>(locales)) {
          switchLocale(context, locale);
          open(context, originalParent);
          context.computeOnClient(client -> Widgets.from(currentScreen(client)));
          context.runOnClient(client -> assertTranslatedKeys());
          setScreen(context, originalParent);
        }

        switchLocale(context, locales.contains("en_us") ? "en_us" : originalLocale);
        open(context, originalParent);
        ensureEnabledScreen(context, originalParent);
        checkDisabledLock(context, originalParent);
        open(context, originalParent);
        exerciseControls(context);
        checkKeyboardSelection(context);
        checkGenerationConfirmation(context);
        context.runOnClient(client -> ReleaseGuiInput.key(InputConstants.KEY_ESCAPE));
        context.waitFor(client -> !isSettings(ReleaseViewport.currentScreen(client)), WAIT_TICKS);
      } finally {
        try {
          switchLocale(context, originalLocale);
        } finally {
          restoreConfig(context, originalParent, original);
        }
      }
    }
  }

  private static void exerciseControls(ClientGameTestContext context) {
    context.runOnClient(
        client -> {
          Widgets widgets = Widgets.from(currentScreen(client));
          JsonObject before = settings();
          ReleaseGuiInput.click(-1, -1);
          if (!before.equals(settings())) {
            throw new AssertionError(
                "Clicking outside settings controls changed the configuration");
          }

          click(widgets.format());
          String format = settings().get("pixelFormat").getAsString();
          if (format.equals(before.get("pixelFormat").getAsString())) {
            throw new AssertionError("Format widget did not update persisted settings");
          }

          float oldStrength = before.get("strength").getAsFloat();
          float targetStrength = oldStrength < 3.9f ? 4.0f : 0.5f;
          slide(widgets.strength(), (targetStrength - 0.5) / 3.5);
          if (Float.compare(settings().get("strength").getAsFloat(), oldStrength) == 0) {
            throw new AssertionError("Strength slider did not update persisted settings");
          }

          int oldSize = before.get("stbnSize").getAsInt();
          int targetSize = oldSize == 16 ? 256 : 16;
          slide(widgets.size(), log2(targetSize / 16.0) / 4.0);
          if (settings().get("stbnSize").getAsInt() != targetSize) {
            throw new AssertionError("Noise size slider did not save " + targetSize);
          }

          int oldDepth = before.get("stbnDepth").getAsInt();
          int targetDepth = oldDepth == 8 ? 128 : 8;
          slide(widgets.depth(), log2(targetDepth / 8.0) / 4.0);
          if (settings().get("stbnDepth").getAsInt() != targetDepth) {
            throw new AssertionError("Noise depth slider did not save " + targetDepth);
          }
          for (int exponent = 0; exponent <= 4; exponent++) {
            slide(widgets.size(), exponent / 4.0);
            slide(widgets.depth(), exponent / 4.0);
            if (settings().get("stbnSize").getAsInt() != (16 << exponent)
                || settings().get("stbnDepth").getAsInt() != (8 << exponent)) {
              throw new AssertionError("Noise sliders did not select step " + exponent);
            }
          }

          checkSeedInput(widgets);
          replaceSeed(widgets.seed(), "24681357");
          if (settings().get("stbnSeed").getAsLong() != 24681357L) {
            throw new AssertionError("Seed edit did not persist");
          }
          boolean chat = before.get("notifyChat").getAsBoolean();
          boolean toast = before.get("notifyToast").getAsBoolean();
          click(widgets.chat());
          click(widgets.toast());
          JsonObject after = settings();
          if (after.get("notifyChat").getAsBoolean() == chat
              || after.get("notifyToast").getAsBoolean() == toast) {
            throw new AssertionError("Notification controls did not save their changes");
          }

          slide(widgets.size(), 0.0);
          slide(widgets.depth(), 0.0);
          replaceSeed(widgets.seed(), "74123");
          if (settings().get("stbnSize").getAsInt() != 16
              || settings().get("stbnDepth").getAsInt() != 8
              || settings().get("stbnSeed").getAsLong() != 74123L) {
            throw new AssertionError("Could not set bounded generation fixture values");
          }
        });
  }

  private static void checkKeyboardSelection(ClientGameTestContext context) {
    context.runOnClient(client -> click(Widgets.from(currentScreen(client)).seed()));
    context.getInput().holdControl();
    try {
      context.runOnClient(client -> ReleaseGuiInput.selectAll());
    } finally {
      context.getInput().releaseControl();
    }
    context.runOnClient(
        client -> {
          EditBox seed = Widgets.from(currentScreen(client)).seed();
          if (!seed.getHighlighted().equals(seed.getValue())) {
            throw new AssertionError("Control+A did not select the seed text");
          }
        });
    context.getInput().typeChars("74021");
    context.runOnClient(
        client -> {
          Widgets widgets = Widgets.from(currentScreen(client));
          if (!widgets.seed().getValue().equals("74021")
              || settings().get("stbnSeed").getAsLong() != 74021) {
            throw new AssertionError(
                "Typing did not replace the selected seed: " + widgets.seed().getValue());
          }
          replaceSeed(widgets.seed(), "74123");
        });
  }

  @SuppressWarnings("ReferenceEquality")
  private static void checkSeedInput(Widgets widgets) {
    replaceSeed(widgets.seed(), "-");
    if (widgets.generate().active || settings().get("stbnSeed").getAsLong() != 0) {
      throw new AssertionError("Incomplete seed changed settings or enabled generation");
    }
    setMode(widgets.mode(), "DISABLED");
    setMode(widgets.mode(), "ENABLED");
    if (widgets.generate().active) {
      throw new AssertionError("Enabling mode allowed generation with an incomplete seed");
    }
    replaceSeed(widgets.seed(), "-42");
    if (settings().get("stbnSeed").getAsLong() != -42 || !widgets.generate().active) {
      throw new AssertionError("Typed negative seed did not save or enable generation");
    }
    ReleaseGuiInput.key(InputConstants.KEY_BACKSPACE);
    ReleaseGuiInput.type("7");
    if (settings().get("stbnSeed").getAsLong() != -47) {
      throw new AssertionError("Seed caret/backspace input did not update settings");
    }
    for (long value : new long[] {Long.MIN_VALUE, Long.MAX_VALUE}) {
      replaceSeed(widgets.seed(), Long.toString(value));
      if (settings().get("stbnSeed").getAsLong() != value || !widgets.generate().active) {
        throw new AssertionError("Seed input did not accept " + value);
      }
    }
    for (String invalid : List.of("9223372036854775808", "-9223372036854775809", "abc")) {
      replaceSeed(widgets.seed(), invalid);
      if (widgets.generate().active) {
        throw new AssertionError("Invalid seed enabled generation: " + invalid);
      }
    }
    replaceSeed(widgets.seed(), "");
    if (settings().get("stbnSeed").getAsLong() != 0 || !widgets.generate().active) {
      throw new AssertionError("Clearing the seed did not select zero");
    }
    ReleaseGuiInput.key(InputConstants.KEY_TAB);
    if (Objects.requireNonNull(currentScreen(Minecraft.getInstance())).getFocused()
        != widgets.generate()) {
      throw new AssertionError("Tab did not move seed focus to Generate");
    }
  }

  private static void replaceSeed(EditBox seed, String value) {
    click(seed);
    if (!seed.isFocused()) throw new AssertionError("Click did not focus the seed input");
    ReleaseGuiInput.key(InputConstants.KEY_HOME);
    int length = seed.getValue().length();
    for (int i = 0; i < length; i++) ReleaseGuiInput.key(InputConstants.KEY_DELETE);
    ReleaseGuiInput.type(value);
    if (!seed.getValue().equals(value)) {
      throw new AssertionError(
          "Seed keyboard input differs: expected " + value + ", got " + seed.getValue());
    }
  }

  private static void checkGenerationConfirmation(ClientGameTestContext context) {
    int cancelLogOffset = log().length();
    context.runOnClient(
        client -> {
          ReleaseGuiInput.key(InputConstants.KEY_TAB);
          ReleaseGuiInput.key(InputConstants.KEY_RETURN);
        });
    context.waitForScreen(ConfirmScreen.class);
    context.runOnClient(client -> ReleaseGuiInput.key(InputConstants.KEY_ESCAPE));
    context.waitFor(client -> isSettings(ReleaseViewport.currentScreen(client)), WAIT_TICKS);
    clickGenerate(context);
    context.waitForScreen(ConfirmScreen.class);
    context.runOnClient(
        client -> click(Widgets.find(currentScreen(client), Button.class, "gui.no")));
    context.waitFor(client -> isSettings(ReleaseViewport.currentScreen(client)), WAIT_TICKS);
    context.waitTicks(3);
    String canceled = logSuffix(cancelLogOffset);
    if (canceled.contains(GENERATION_STARTED) || canceled.contains(CACHE_HIT)) {
      throw new AssertionError("Canceling STBN confirmation started generation");
    }

    int confirmLogOffset = log().length();
    clickGenerate(context);
    context.waitForScreen(ConfirmScreen.class);
    context.runOnClient(
        client -> click(Widgets.find(currentScreen(client), Button.class, "gui.yes")));
    context.waitFor(client -> isSettings(ReleaseViewport.currentScreen(client)), WAIT_TICKS);
    context.waitFor(client -> hasGenerationStart(logSuffix(confirmLogOffset)), WAIT_TICKS);
    context.waitFor(client -> hasGenerationCompletion(logSuffix(confirmLogOffset)), WAIT_TICKS);
  }

  private static void clickGenerate(ClientGameTestContext context) {
    context.runOnClient(client -> click(Widgets.from(currentScreen(client)).generate()));
  }

  private static void checkDisabledLock(
      ClientGameTestContext context, @Nullable Screen originalParent) {
    context.runOnClient(client -> setMode(Widgets.from(currentScreen(client)).mode(), "DISABLED"));
    context.runOnClient(
        client -> {
          Widgets widgets = Widgets.from(currentScreen(client));
          if (!widgets.mode().active || !widgets.done().active) {
            throw new AssertionError("Mode and Done controls must remain available when disabled");
          }
          for (AbstractWidget widget : widgets.lockedControls()) {
            if (widget.active) {
              throw new AssertionError("Disabled mode left a settings control active: " + widget);
            }
          }
          long savedSeed = settings().get("stbnSeed").getAsLong();
          String savedText = widgets.seed().getValue();
          click(widgets.seed());
          ReleaseGuiInput.type("99173");
          if (settings().get("stbnSeed").getAsLong() != savedSeed
              || !widgets.seed().getValue().equals(savedText)) {
            throw new AssertionError("Disabled mode allowed a locked seed change");
          }
          setMode(widgets.mode(), "ENABLED");
          for (AbstractWidget widget : widgets.lockedControls()) {
            if (!widget.active) {
              throw new AssertionError(
                  "Enabling mode did not unlock a settings control: " + widget);
            }
          }
        });
    context.runOnClient(client -> setMode(Widgets.from(currentScreen(client)).mode(), "DISABLED"));
    clickDone(context);
    open(context, originalParent);
    context.runOnClient(
        client -> {
          Widgets widgets = Widgets.from(currentScreen(client));
          setMode(widgets.mode(), "ENABLED");
          for (AbstractWidget widget : widgets.lockedControls()) {
            if (!widget.active)
              throw new AssertionError("Initially disabled screen did not unlock: " + widget);
          }
          long seed = settings().get("stbnSeed").getAsLong();
          long edited = seed == Long.MAX_VALUE ? 0 : seed + 1;
          replaceSeed(widgets.seed(), Long.toString(edited));
          if (settings().get("stbnSeed").getAsLong() != edited) {
            throw new AssertionError("Initially disabled screen ignored seed input after enabling");
          }
          replaceSeed(widgets.seed(), Long.toString(seed));
        });
    clickDone(context);
  }

  private static void ensureEnabledScreen(
      ClientGameTestContext context, @Nullable Screen originalParent) {
    boolean changed =
        context.computeOnClient(
            client -> {
              Widgets widgets = Widgets.from(currentScreen(client));
              if ("ENABLED".equals(settings().get("mode").getAsString())) return false;
              setMode(widgets.mode(), "ENABLED");
              return true;
            });
    if (changed) {
      setScreen(context, originalParent);
      open(context, originalParent);
    }
  }

  private static void restoreConfig(
      ClientGameTestContext context, @Nullable Screen originalParent, JsonObject original) {
    setScreen(context, originalParent);
    open(context, originalParent);
    ensureEnabledScreen(context, originalParent);
    context.runOnClient(
        client -> {
          Widgets widgets = Widgets.from(currentScreen(client));
          JsonObject current = settings();
          setFormat(widgets.format(), original.get("pixelFormat").getAsString());
          setStrength(widgets.strength(), original.get("strength").getAsFloat());
          setNoiseSlider(
              widgets.size(),
              current.get("stbnSize").getAsInt(),
              original.get("stbnSize").getAsInt(),
              16);
          current = settings();
          setNoiseSlider(
              widgets.depth(),
              current.get("stbnDepth").getAsInt(),
              original.get("stbnDepth").getAsInt(),
              8);
          replaceSeed(widgets.seed(), original.get("stbnSeed").getAsString());
          if (settings().get("stbnSeed").getAsLong() != original.get("stbnSeed").getAsLong()) {
            throw new AssertionError("Could not restore seed through the settings screen");
          }
          if (settings().get("notifyChat").getAsBoolean()
              != original.get("notifyChat").getAsBoolean()) click(widgets.chat());
          if (settings().get("notifyToast").getAsBoolean()
              != original.get("notifyToast").getAsBoolean()) click(widgets.toast());
          setMode(widgets.mode(), original.get("mode").getAsString());
        });
    clickDone(context);
    context.waitFor(client -> original.equals(settings()), WAIT_TICKS);
  }

  private static void setMode(CycleButton<?> button, String target) {
    for (int i = 0; i < 3; i++) {
      if (target.equals(settings().get("mode").getAsString())) return;
      click(button);
    }
    if (!target.equals(settings().get("mode").getAsString())) {
      throw new AssertionError("Mode widget did not select " + target);
    }
  }

  private static void setFormat(CycleButton<?> button, String target) {
    if (target.equals(settings().get("pixelFormat").getAsString())) return;
    click(button);
    if (!target.equals(settings().get("pixelFormat").getAsString())) {
      throw new AssertionError("Format widget did not restore " + target);
    }
  }

  private static void setStrength(AbstractSliderButton slider, float strength) {
    slide(slider, (strength - 0.5) / 3.5);
    if (Float.compare(settings().get("strength").getAsFloat(), strength) != 0) {
      throw new AssertionError("Strength slider did not restore " + strength);
    }
  }

  private static void setNoiseSlider(
      AbstractSliderButton slider, int current, int target, int minimum) {
    if (current == target) return;
    double fraction = log2(target / (double) minimum) / 4.0;
    slide(slider, fraction);
    if (settings().get(minimum == 16 ? "stbnSize" : "stbnDepth").getAsInt() != target) {
      throw new AssertionError("Noise slider did not restore " + target);
    }
  }

  private static Set<String> availableLocales(ClientGameTestContext context) {
    return context.computeOnClient(
        client ->
            client
                .getResourceManager()
                .listResources(
                    "lang",
                    id -> id.getNamespace().equals("cbbg") && id.getPath().endsWith(".json"))
                .keySet()
                .stream()
                .map(id -> localeName(id.getPath()))
                .collect(Collectors.toSet()));
  }

  private static String localeName(String path) {
    return path.substring("lang/".length(), path.length() - ".json".length());
  }

  private static void switchLocale(ClientGameTestContext context, String locale) {
    String selected = context.computeOnClient(client -> client.getLanguageManager().getSelected());
    if (selected.equals(locale)) return;
    CompletableFuture<Void> reload =
        context.computeOnClient(
            client -> {
              client.getLanguageManager().setSelected(locale);
              return client.reloadResourcePacks();
            });
    context.waitFor(client -> reload.isDone(), WAIT_TICKS);
    reload.join();
  }

  private static void assertTranslatedKeys() {
    for (String key : LABELS) assertTranslated(key);
    for (String key : TRANSLATIONS) assertTranslated(key);
  }

  private static void assertTranslated(String key) {
    String value = Component.translatable(key).getString();
    if (value.isBlank() || value.equals(key)) {
      throw new AssertionError("Missing packaged translation for " + key);
    }
  }

  private static void open(ClientGameTestContext context, @Nullable Screen parent) {
    context.setScreen(() -> configScreen(parent));
    context.waitFor(
        client ->
            isSettings(ReleaseViewport.currentScreen(client))
                && !ReleaseViewport.loadingOverlay(client),
        WAIT_TICKS);
    context.waitTick();
  }

  private static Screen configScreen(@Nullable Screen parent) {
    String mapped = ReleaseMapping.className(SCREEN_CLASS);
    try {
      Class<? extends Screen> type = Class.forName(mapped).asSubclass(Screen.class);
      Constructor<? extends Screen> constructor = type.getConstructor(Screen.class);
      return constructor.newInstance(parent);
    } catch (ClassNotFoundException
        | NoSuchMethodException
        | InstantiationException
        | IllegalAccessException
        | InvocationTargetException failure) {
      throw new LinkageError("Could not construct the mapped CBBG settings screen", failure);
    }
  }

  @SuppressWarnings("ReferenceEquality")
  private static void setScreen(ClientGameTestContext context, @Nullable Screen screen) {
    context.runOnClient(client -> ReleaseViewport.setScreen(client, screen));
    context.waitFor(client -> ReleaseViewport.currentScreen(client) == screen, WAIT_TICKS);
  }

  private static void clickDone(ClientGameTestContext context) {
    context.runOnClient(client -> click(Widgets.from(currentScreen(client)).done()));
    context.waitFor(client -> !isSettings(ReleaseViewport.currentScreen(client)), WAIT_TICKS);
  }

  private static boolean isSettings(@Nullable Screen screen) {
    return screen != null
        && screen
            .getTitle()
            .getString()
            .equals(Component.translatable("cbbg.config.title").getString());
  }

  private static @Nullable Screen currentScreen(Minecraft client) {
    return ReleaseViewport.currentScreen(client);
  }

  private static void click(AbstractWidget widget) {
    ReleaseGuiInput.click(
        widget.getX() + widget.getWidth() / 2.0, widget.getY() + widget.getHeight() / 2.0);
  }

  private static void slide(AbstractSliderButton widget, double fraction) {
    double x = widget.getX() + 4 + fraction * (widget.getWidth() - 8);
    ReleaseGuiInput.drag(
        widget.getX() + widget.getWidth() / 2.0, widget.getY() + widget.getHeight() / 2.0, x);
  }

  private static double log2(double value) {
    return Math.log(value) / Math.log(2);
  }

  private static void assertTranslatedWidget(AbstractWidget widget, String key) {
    String label = Component.translatable(key).getString();
    if (label.equals(key) || !widget.getMessage().getString().contains(label)) {
      throw new AssertionError("Public settings widget lacks its translation: " + key);
    }
  }

  private static boolean hasGenerationStart(String lines) {
    return lines.contains(GENERATION_STARTED)
        || lines.contains(CACHE_HIT)
        || lines.contains("Checking STBN cache (")
        || lines.contains("STBN preparation complete in ");
  }

  private static boolean hasGenerationCompletion(String lines) {
    return lines.contains("STBN Images generated from math fields.")
        || lines.contains("STBN Frames loaded from cache.")
        || lines.contains("STBN preparation complete in ");
  }

  private static String log() {
    Path path = FabricLoader.getInstance().getGameDir().resolve("logs/latest.log");
    try {
      return Files.readString(path);
    } catch (IOException failure) {
      throw new AssertionError("Could not read client log " + path, failure);
    }
  }

  private static String logSuffix(int offset) {
    String current = log();
    return current.substring(Math.min(offset, current.length()));
  }

  private static JsonObject settings() {
    Path path = FabricLoader.getInstance().getConfigDir().resolve("cbbg.json");
    try (var reader = Files.newBufferedReader(path)) {
      JsonObject result = JsonParser.parseReader(reader).getAsJsonObject();
      if (!result.has("notifyChat")) result.addProperty("notifyChat", true);
      if (!result.has("notifyToast")) result.addProperty("notifyToast", true);
      return result;
    } catch (IOException failure) {
      throw new AssertionError("Could not read persisted CBBG settings " + path, failure);
    }
  }

  record Widgets(
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
    static Widgets from(@Nullable Screen screen) {
      if (!isSettings(screen)) throw new AssertionError("Expected packaged CBBG config screen");
      return new Widgets(
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

    List<AbstractWidget> lockedControls() {
      return List.of(format, strength, size, depth, seed, generate, chat, toast);
    }

    private static <T extends AbstractWidget> T find(
        @Nullable Screen screen, Class<T> type, String key) {
      String label = Component.translatable(key).getString();
      List<T> matches =
          Objects.requireNonNull(screen).children().stream()
              .filter(type::isInstance)
              .map(child -> Objects.requireNonNull(type.cast(child)))
              .filter(widget -> widget.getMessage().getString().contains(label))
              .toList();
      if (label.equals(key) || matches.size() != 1) {
        throw new AssertionError("Expected one translated public widget for " + key);
      }
      AbstractWidget widget = matches.getFirst();
      assertTranslatedWidget(widget, key);
      return type.cast(widget);
    }
  }
}
