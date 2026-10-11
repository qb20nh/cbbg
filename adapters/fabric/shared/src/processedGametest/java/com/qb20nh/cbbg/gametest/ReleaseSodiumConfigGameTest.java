package com.qb20nh.cbbg.gametest;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import org.jspecify.annotations.NullMarked;

@NullMarked
public final class ReleaseSodiumConfigGameTest implements FabricClientGameTest {
  // The parent screen must be the same instance after returning from settings.
  @SuppressWarnings({"unchecked", "ReferenceEquality"})
  @Override
  public void runTest(ClientGameTestContext context) {
    if (!FabricLoader.getInstance().isModLoaded("sodium")) {
      return;
    }
    if (!settingsApiAvailable()) return;
    var original = context.computeOnClient(UtilitiesBackend::screen);
    var parent = context.computeOnClient(client -> new TitleScreen());
    try {
      context.runOnClient(
          client -> {
            try {
              Object config =
                  Objects.requireNonNull(
                      Class.forName("net.caffeinemc.mods.sodium.client.config.ConfigManager")
                          .getField("CONFIG")
                          .get(null));
              var options =
                  (List<?>)
                      Objects.requireNonNull(
                          config.getClass().getMethod("getModOptions").invoke(config));
              for (Object mod : options) {
                if (!"cbbg".equals(mod.getClass().getMethod("configId").invoke(mod))) continue;
                var pages =
                    (List<?>) Objects.requireNonNull(mod.getClass().getMethod("pages").invoke(mod));
                if (pages.size() != 1) throw new AssertionError("Expected one CBBG page in Sodium");
                Object page = pages.getFirst();
                var open =
                    (Consumer<Screen>)
                        Objects.requireNonNull(
                            page.getClass().getMethod("currentScreenConsumer").invoke(page));
                open.accept(parent);
                return;
              }
              throw new AssertionError("Sodium did not register CBBG settings");
            } catch (ReflectiveOperationException failure) {
              throw new LinkageError("Could not open CBBG settings through Sodium", failure);
            }
          });
      context.waitFor(
          client -> {
            Screen screen = UtilitiesBackend.screen(client);
            return screen != null
                && screen
                    .getTitle()
                    .getString()
                    .equals(
                        net.minecraft.network.chat.Component.translatable("cbbg.config.title")
                            .getString());
          },
          200);
      context.clickScreenButton("cbbg.config.button.done");
      context.runOnClient(
          client -> {
            if (UtilitiesBackend.screen(client) != parent) {
              throw new AssertionError("CBBG settings did not return to their parent");
            }
          });
    } finally {
      context.setScreen(() -> original);
    }
  }

  private static boolean settingsApiAvailable() {
    boolean available =
        Objects.requireNonNull(ReleaseSodiumConfigGameTest.class.getClassLoader())
                .getResource("net/caffeinemc/mods/sodium/api/config/ConfigEntryPoint.class")
            != null;
    JsonObject record = new JsonObject();
    record.addProperty(
        "sodiumVersion",
        FabricLoader.getInstance()
            .getModContainer("sodium")
            .orElseThrow()
            .getMetadata()
            .getVersion()
            .getFriendlyString());
    record.addProperty("settingsApiAvailable", available);
    Path evidence = Path.of(Objects.requireNonNull(System.getProperty("cbbg.test.evidence")));
    try {
      Files.createDirectories(evidence);
      Files.writeString(evidence.resolve("sodium-config-api.json"), new Gson().toJson(record));
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    }
    return available;
  }
}
