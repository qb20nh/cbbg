package com.qb20nh.cbbg;

/** Escapes emitted by the canonical JSON-to-lang resource generator. */
public final class LegacyLanguage {
    private LegacyLanguage() {}

    public static String decode(String value) {
        StringBuilder result = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length()) {
                char next = value.charAt(i + 1);
                if (next == 'n' || next == 'r' || next == 't' || next == '\\') {
                    c = next == 'n' ? '\n' : next == 'r' ? '\r' : next == 't' ? '\t' : '\\';
                    i++;
                }
            }
            result.append(c);
        }
        return result.toString();
    }
}
