package com.qb20nh.cbbg.gametest;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.NullMarked;

/** Resolves source names against the mapping packaged with the optimized test driver. */
@NullMarked
final class ReleaseMapping {
  private static final List<String> LINES = read();

  private ReleaseMapping() {}

  static String className(String original) {
    for (String line : LINES) {
      if (line.startsWith(original + " -> ")) {
        return line.substring(original.length() + 4, line.length() - 1);
      }
    }
    throw new AssertionError("Missing CBBG class mapping: " + original);
  }

  static String memberName(String owner, String original) {
    String heading = owner + " -> " + className(owner) + ":";
    boolean inClass = false;
    for (String line : LINES) {
      if (line.equals(heading)) {
        inClass = true;
      } else if (inClass
          && !line.isEmpty()
          && !line.startsWith("#")
          && !Character.isWhitespace(line.charAt(0))) {
        break;
      } else if (inClass && line.contains(original + " -> ")) {
        return line.substring(line.lastIndexOf(" -> ") + 4);
      }
    }
    throw new AssertionError("Missing CBBG member mapping: " + owner + "." + original);
  }

  private static List<String> read() {
    List<String> lines = new ArrayList<>(read("/cbbg-test.map", true));
    lines.addAll(read("/cbbg-library-test.map", false));
    return lines;
  }

  private static List<String> read(String path, boolean required) {
    var resource = ReleaseMapping.class.getResourceAsStream(path);
    if (resource == null) {
      if (required) throw new AssertionError("Packaged test driver lacks " + path.substring(1));
      return List.of();
    }
    try (var reader = new BufferedReader(new InputStreamReader(resource, StandardCharsets.UTF_8))) {
      return reader.lines().toList();
    } catch (IOException failure) {
      throw new AssertionError("Cannot read packaged CBBG mapping", failure);
    }
  }
}
