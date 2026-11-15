package com.edumy.shared.text;

/**
 * Makes free text typed by a user safe to put in a log line (log injection, CWE-117): a value such as
 * {@code "x\r\n2026-… INFO  Payment success …"} must not be able to forge a second log entry. Every control character
 * (CR, LF, TAB, ESC…, and the Unicode line / paragraph separators) becomes {@code _} and the text is cut at
 * {@value #MAX_LENGTH} characters. Ids, codes and enum values need no sanitizing; log those instead whenever they are
 * enough (NFR-08).
 */
public final class LogSanitizer {

    /** Longest free text kept in one log argument. */
    public static final int MAX_LENGTH = 200;

    private static final char REPLACEMENT = '_';

    private LogSanitizer() {
    }

    /** {@code text} on one line, at most {@value #MAX_LENGTH} characters (+ a marker when cut); {@code null} → "-". */
    public static String clean(String text) {
        if (text == null) {
            return "-";
        }
        int kept = Math.min(text.length(), MAX_LENGTH);
        StringBuilder out = new StringBuilder(kept + 16);
        for (int i = 0; i < kept; i++) {
            char c = text.charAt(i);
            out.append(isLineBreaking(c) ? REPLACEMENT : c);
        }
        if (text.length() > MAX_LENGTH) {
            out.append("…(+").append(text.length() - MAX_LENGTH).append(')');
        }
        return out.toString();
    }

    private static boolean isLineBreaking(char c) {
        return Character.isISOControl(c) || c == ' ' || c == ' ';
    }
}
