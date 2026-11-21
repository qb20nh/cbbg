package com.qb20nh.cbbg;

import org.jspecify.annotations.NullMarked;

/** Escapes emitted by the canonical JSON-to-lang resource generator. */
@NullMarked
public final class LegacyLanguage {
  private LegacyLanguage() {}

  public static String decode(String value) {
    StringBuilder result = new StringBuilder(value.length());
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == '\\' && i + 1 < value.length()) {
        char next = value.charAt(i + 1);
        switch (next) {
          case 'n':
            c = '\n';
            i++;
            break;
          case 'r':
            c = '\r';
            i++;
            break;
          case 't':
            c = '\t';
            i++;
            break;
          case '\\':
            i++;
            break;
          default:
            break;
        }
      }
      result.append(c);
    }
    return result.toString();
  }
}
