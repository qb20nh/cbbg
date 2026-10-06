package com.qb20nh.cbbg.gametest;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Properties;
import org.jspecify.annotations.NullMarked;

@NullMarked
final class ReleaseGameNames {
  private static final Properties NAMES = load();

  private ReleaseGameNames() {}

  static String field(Class<?> owner, String name) {
    return Objects.requireNonNull(NAMES.getProperty("field|" + owner.getName() + "|" + name, name));
  }

  static String noArgMethod(Class<?> owner, String name) {
    return Objects.requireNonNull(
        NAMES.getProperty("method|" + owner.getName() + "|" + name, name));
  }

  private static Properties load() {
    Properties names = new Properties();
    try (InputStream input =
        ReleaseGameNames.class.getResourceAsStream("/cbbg-game-names.properties")) {
      if (input != null) names.load(new InputStreamReader(input, StandardCharsets.UTF_8));
    } catch (IOException failure) {
      throw new LinkageError("Cannot read packaged Minecraft test mappings", failure);
    }
    return names;
  }
}
