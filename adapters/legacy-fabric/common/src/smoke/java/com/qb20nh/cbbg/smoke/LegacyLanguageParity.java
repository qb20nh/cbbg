package com.qb20nh.cbbg.smoke;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.resource.language.TranslationStorage;

final class LegacyLanguageParity {
    static int run() throws Exception {
        Path directory = Paths.get(System.getProperty("cbbg.test.translations"));
        List<Path> files;
        try (Stream<Path> paths = Files.list(directory)) {
            files = paths.filter(path -> path.toString().endsWith(".json")).sorted().collect(Collectors.toList());
        }
        if (files.isEmpty()) throw new AssertionError("No canonical translations found");
        boolean uppercase = MinecraftClient.getInstance().options.language.contains("_")
                && !MinecraftClient.getInstance().options.language.equals(
                        MinecraftClient.getInstance().options.language.toLowerCase(Locale.ROOT));
        int checks = 0;
        Object[] args = {"one", "two", "three", "four", "five", "six"};
        for (Path file : files) {
            String locale = file.getFileName().toString().replace(".json", "");
            if (uppercase) {
                String[] parts = locale.split("_", 2);
                locale = parts[0] + "_" + parts[1].toUpperCase(Locale.ROOT);
            }
            TranslationStorage storage = new TranslationStorage();
            storage.load(MinecraftClient.getInstance().getResourceManager(), Arrays.asList(locale));
            JsonObject entries;
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                entries = new JsonParser().parse(reader).getAsJsonObject();
            }
            for (Map.Entry<String, JsonElement> entry : entries.entrySet()) {
                String expected = String.format(entry.getValue().getAsString(), args);
                String actual = storage.translateAndFormat(entry.getKey(), args);
                if (!expected.equals(actual)) throw new AssertionError(locale + ": " + entry.getKey()
                        + " expected=" + expected + " actual=" + actual);
                checks++;
            }
        }
        TranslationStorage escaped = new TranslationStorage();
        // Load twice to catch decoding each resource rather than the final merged values.
        escaped.load(MinecraftClient.getInstance().getResourceManager(),
                Arrays.asList("cbbg_parity", "cbbg_parity"));
        String expected = "literal \\n; newline \n; slash \\; tab \t; return \r; unknown \\q; equals a=b";
        if (!expected.equals(escaped.translateAndFormat("cbbg.smoke.escaped", new Object[0]))) {
            throw new AssertionError("Legacy escape decoding lost literal characters or line breaks");
        }
        if (!"leave \\n and \\\\ intact".equals(
                escaped.translateAndFormat("foreign.smoke.escaped", new Object[0]))) {
            throw new AssertionError("CBBG decoded another namespace's translation");
        }
        return checks + 2;
    }
}
