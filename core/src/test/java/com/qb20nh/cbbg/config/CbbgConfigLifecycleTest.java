package com.qb20nh.cbbg.config;

import static org.junit.jupiter.api.Assertions.*;

import com.qb20nh.cbbg.LegacyCommandGrammar;
import java.nio.file.Path;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@NullMarked
class CbbgConfigLifecycleTest {
  @TempDir Path directory;

  @Test
  void loaderInitializesOnceAndSettersPreserveOtherSettings() {
    assertThrows(IllegalStateException.class, CbbgConfig::get);
    Path path = directory.resolve("cbbg.json");
    CbbgConfig.configure(path, (message, error) -> fail(message));
    CbbgConfig.setMode(CbbgConfig.Mode.DEMO);
    CbbgConfig.setPixelFormat(CbbgConfig.PixelFormat.RGBA32F);
    CbbgConfig.setStbnSize(64);
    CbbgConfig.setStbnDepth(32);
    CbbgConfig.setStbnSeed(12345);
    CbbgConfig.setStrength(2);
    CbbgConfig.setNotifyChat(false);
    CbbgConfig.setNotifyToast(false);
    CbbgConfig.configure(path, (message, error) -> fail(message));
    CbbgConfig.setMode(null);
    CbbgConfig.setPixelFormat(null);
    CbbgConfig expected =
        new CbbgConfig(
            CbbgConfig.Mode.DEMO, CbbgConfig.PixelFormat.RGBA32F, 64, 32, 12345, 2, false, false);
    assertEquals(expected, CbbgConfig.get());
    assertEquals(expected, CbbgConfig.load(path, (message, error) -> fail(message)));
    assertThrows(
        IllegalStateException.class,
        () -> CbbgConfig.configure(directory.resolve("other.json"), (message, error) -> {}));

    // Exercise commands in this initialized singleton lifecycle, avoiding
    // test-only reset hooks in the production configuration API.
    int[] calls = new int[3];
    LegacyCommandGrammar.Feedback feedback =
        new LegacyCommandGrammar.Feedback() {
          @Override
          public Object translate(String key) {
            return key;
          }

          @Override
          public void send(boolean error, String key, Object... args) {
            if (error) calls[0]++;
          }
        };
    Runnable generate = () -> calls[1]++;
    Runnable reload = () -> calls[2]++;
    for (String command :
        new String[] {
          "format set rgba8",
          "stbn size 33",
          "stbn depth 256",
          "stbn seed 9223372036854775808",
          "notification toast true"
        }) {
      int errors = calls[0];
      assertTrue(
          LegacyCommandGrammar.execute("/cbbg " + command, false, feedback, generate, reload));
      assertTrue(calls[0] > errors, command);
      assertEquals(expected, CbbgConfig.get(), command);
      assertEquals(expected, CbbgConfig.load(path, (message, error) -> fail(message)), command);
    }
    assertEquals(0, calls[1]);
    assertEquals(0, calls[2]);
    assertFalse(
        LegacyCommandGrammar.execute("/other stbn generate", true, feedback, generate, reload));
    assertTrue(
        LegacyCommandGrammar.execute("/cbbg stbn generate", true, feedback, generate, reload));
    assertEquals(1, calls[1]);
    assertEquals(0, calls[2]);
    assertEquals(expected, CbbgConfig.get());
    assertTrue(LegacyCommandGrammar.execute("/cbbg stbn reset", true, feedback, generate, reload));
    assertEquals(1, calls[1]);
    assertEquals(1, calls[2]);
    CbbgConfig reset =
        new CbbgConfig(
            CbbgConfig.Mode.DEMO, CbbgConfig.PixelFormat.RGBA32F, 128, 64, 0, 2, false, false);
    assertEquals(reset, CbbgConfig.get());
    assertEquals(reset, CbbgConfig.load(path, (message, error) -> fail(message)));
  }
}
