package com.qb20nh.cbbg.gametest;

import com.qb20nh.cbbg.config.gui.CbbgConfigScreen;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.TreeMap;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.locale.Language;

/** Checks every packaged locale through Minecraft's resource reload and config UI. */
public final class LocalesGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        String original = context.computeOnClient(client -> client.getLanguageManager().getSelected());
        var originalScreen = context.computeOnClient(client -> client.gui.screen());
        Map<String, Map<String, String>> locales = context.computeOnClient(client -> {
            Map<String, Map<String, String>> result = new TreeMap<>();
            var resources = client.getResourceManager().listResources("lang",
                    id -> id.getNamespace().equals("cbbg") && id.getPath().endsWith(".json"));
            resources.forEach((id, resource) -> {
                String locale = id.getPath().substring("lang/".length(), id.getPath().length() - 5);
                Map<String, String> expected = new TreeMap<>();
                try (var stream = resource.open()) {
                    Language.loadFromJson(stream, expected::put);
                } catch (IOException failure) {
                    throw new AssertionError("Could not read packaged locale " + locale, failure);
                }
                if (expected.isEmpty() || client.getLanguageManager().getLanguage(locale) == null) {
                    throw new AssertionError("Empty or unavailable packaged locale " + locale);
                }
                result.put(locale, expected);
            });
            if (!result.containsKey("en_us") || result.size() < 2) {
                throw new AssertionError("Packaged translations were not discovered");
            }
            return result;
        });
        try {
            for (var entry : locales.entrySet()) {
                String locale = entry.getKey();
                if (!entry.getValue().keySet().equals(locales.get("en_us").keySet())) {
                    throw new AssertionError("Translation keys differ from English for " + locale);
                }
                reload(context, locale);
                context.runOnClient(client -> {
                    if (!client.getLanguageManager().getSelected().equals(locale)) {
                        throw new AssertionError("Language selection did not change to " + locale);
                    }
                    entry.getValue().forEach((key, expected) -> {
                        if (!expected.equals(Language.getInstance().getOrDefault(key))) {
                            throw new AssertionError("Missing or incorrect runtime translation: " + locale + "/" + key);
                        }
                    });
                });
                context.setScreen(() -> new CbbgConfigScreen(null));
                context.waitForScreen(CbbgConfigScreen.class);
                context.waitTicks(3);
                context.runOnClient(client -> {
                    if (!client.gui.screen().getTitle().getString().equals(entry.getValue().get("cbbg.config.title"))) {
                        throw new AssertionError("Config UI did not use the selected locale " + locale);
                    }
                });
                Path screenshot = context.takeScreenshot("cbbg-locale-" + locale);
                Path evidence = Path.of(System.getProperty("cbbg.test.evidence"));
                Files.createDirectories(evidence);
                Files.copy(screenshot, evidence.resolve("locale-" + locale + ".png"),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failure) {
            throw new AssertionError("Could not retain locale screenshots", failure);
        } finally {
            reload(context, original);
            context.setScreen(() -> originalScreen);
        }
    }

    private static void reload(ClientGameTestContext context, String locale) {
        var reload = context.computeOnClient(client -> {
            client.getLanguageManager().setSelected(locale);
            return client.reloadResourcePacks();
        });
        context.waitFor(client -> reload.isDone(), 600);
        reload.join();
        // The reload future completes before the loading overlay finishes fading.
        context.waitFor(client -> client.gui.overlay() == null, 600);
    }
}
