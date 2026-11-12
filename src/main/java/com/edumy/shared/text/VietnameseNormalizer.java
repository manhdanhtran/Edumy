package com.edumy.shared.text;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Accent-free, lowercase form of Vietnamese text for {@code courses.title_normalized} and search keywords
 * (docs/ai/03 §3.2): NFD → strip combining marks → đ/Đ → d → lowercase → collapse spaces.
 * {@code utf8mb4_0900_ai_ci} does not fold đ to d, so this must be done in the application.
 */
public final class VietnameseNormalizer {

    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private VietnameseNormalizer() {
    }

    public static String normalize(String input) {
        if (input == null) {
            return null;
        }
        String result = Normalizer.normalize(input, Normalizer.Form.NFD);
        result = COMBINING_MARKS.matcher(result).replaceAll("");
        result = result.replace('đ', 'd').replace('Đ', 'd');
        result = result.toLowerCase(Locale.ROOT);
        return WHITESPACE.matcher(result).replaceAll(" ").strip();
    }
}
