package com.qb20nh.cbbg;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class LegacyLanguageTest {
  @Test
  void generatedEscapesRoundTripWithoutReinterpretingLiteralBackslashes() {
    String original = "한국어\nline\r\tpath\\new\\test\\";
    String encoded =
        original
            .replace("\\", "\\\\")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    assertEquals(original, LegacyLanguage.decode(encoded));
  }

  @Test
  void unknownEscapesAndTrailingBackslashRemainLiteral() {
    assertEquals("\\u1234\\q\\", LegacyLanguage.decode("\\u1234\\q\\"));
    assertEquals("", LegacyLanguage.decode(""));
  }
}
