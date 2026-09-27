package com.qb20nh.cbbg.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.qb20nh.cbbg.internal.gson.stream.JsonReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.util.Arrays;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Test;

@NullMarked
class PrivateGsonBridgeTest {
  @Test
  void compatibilityBridgeConstructorsHaveNoEffects() throws Exception {
    CodeSource source =
        Objects.requireNonNull(JsonReader.class.getProtectionDomain().getCodeSource());
    Path jar = Paths.get(Objects.requireNonNull(source.getLocation()).toURI());
    String bridgeName = JsonReader.class.getName() + "$1";
    String bridge = javap(jar, bridgeName);
    Matcher superclass = Pattern.compile("extends ([\\w.$]+) \\{").matcher(bridge);
    assertTrue(superclass.find(), bridge);
    assertFalse(bridge.contains("static {};"), bridge);

    String superclassName = Objects.requireNonNull(superclass.group(1));
    assertTrivialConstructor(bridge, bridgeName, superclassName.replace('.', '/'));
    String parent = javap(jar, superclassName);
    assertFalse(parent.contains("static {};"), parent);
    assertTrivialConstructor(parent, superclassName, "java/lang/Object");
  }

  private static void assertTrivialConstructor(
      String bytecode, String className, String superclass) {
    int start = bytecode.indexOf(className + "();");
    assertTrue(start >= 0, bytecode);
    start = bytecode.indexOf("Code:", start);
    assertTrue(start >= 0, bytecode);
    int end = bytecode.indexOf("\n\n", start);
    String[] instructions =
        Arrays.stream(bytecode.substring(start, end < 0 ? bytecode.length() : end).split("\\R"))
            .map(String::trim)
            .filter(line -> line.matches("[0-9]+:.*"))
            .map(line -> line.replaceFirst("^[0-9]+:\\s*", ""))
            .toArray(String[]::new);
    assertEquals(3, instructions.length, bytecode);
    assertEquals("aload_0", instructions[0]);
    assertTrue(
        instructions[1].startsWith("invokespecial ")
            && instructions[1].contains(superclass + ".\"<init>\":()V"),
        bytecode);
    assertEquals("return", instructions[2]);
  }

  private static String javap(Path jar, String className) throws IOException, InterruptedException {
    Path javaHome = Paths.get(System.getProperty("java.home"));
    Path executable = javaHome.resolve("bin/javap");
    if (!Files.isRegularFile(executable)) executable = javaHome.resolve("../bin/javap");
    Process process =
        new ProcessBuilder(
                executable.toString(), "-classpath", jar.toString(), "-p", "-c", className)
            .redirectErrorStream(true)
            .start();
    StringBuilder text = new StringBuilder();
    try (Reader reader = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
      char[] buffer = new char[4096];
      int count;
      while ((count = reader.read(buffer)) != -1) {
        text.append(buffer, 0, count);
      }
    }
    String output = text.toString();
    assertEquals(0, process.waitFor(), output);
    return output;
  }
}
