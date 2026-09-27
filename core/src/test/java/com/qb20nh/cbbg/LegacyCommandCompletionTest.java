package com.qb20nh.cbbg;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.Objects;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Test;

@NullMarked
class LegacyCommandCompletionTest {
  @Test
  void unrelatedCommandsStayWithVanilla() {
    assertNull(LegacyCommandGrammar.suggest("/cbbg-extra mode", true));
    assertNull(LegacyCommandGrammar.suggest("/help", true));
    assertTrue(
        Objects.requireNonNull(LegacyCommandGrammar.suggest("/cbbg unknown ", true)).isEmpty());
  }

  @Test
  void notificationCompletionFollowsNativeToastAvailability() {
    assertEquals(Arrays.asList("chat"), LegacyCommandGrammar.suggest("/cbbg notification ", false));
    assertEquals(
        Arrays.asList("chat", "toast"), LegacyCommandGrammar.suggest("/cbbg notification ", true));
    assertTrue(
        Objects.requireNonNull(LegacyCommandGrammar.suggest("/cbbg notification toast ", false))
            .isEmpty());
    assertEquals(
        Arrays.asList("true", "false"),
        LegacyCommandGrammar.suggest("/cbbg notification toast ", true));
  }

  @Test
  void precisionCompletionExcludesFallbackFormatAndFiltersPrefix() {
    assertFalse(
        Objects.requireNonNull(LegacyCommandGrammar.suggest("/cbbg format set ", true))
            .contains("rgba8"));
    assertEquals(
        Arrays.asList("rgba32f"), LegacyCommandGrammar.suggest("/cbbg format set RGBA3", true));
  }
}
