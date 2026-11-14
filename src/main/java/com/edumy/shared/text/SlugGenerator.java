package com.edumy.shared.text;

import java.util.regex.Pattern;

/**
 * URL slug of Vietnamese text for {@code categories.slug} / {@code courses.slug}: {@link VietnameseNormalizer} (accents
 * and đ removed, lowercase), then every run of characters other than {@code a-z0-9} becomes one hyphen, without a
 * leading or trailing hyphen. The result always matches the DDD CHECK {@code ^[a-z0-9]+(-[a-z0-9]+)*$}
 * ({@code ck_categories_slug}, {@code ck_courses_slug}) or is empty (input without any letter or digit): the caller
 * decides what an empty slug means.
 */
public final class SlugGenerator {

    private static final Pattern NOT_SLUG_CHARS = Pattern.compile("[^a-z0-9]+");

    private SlugGenerator() {
    }

    /** {@code "Lập trình Java & Spring"} → {@code "lap-trinh-java-spring"}; {@code null} → {@code ""}. */
    public static String slugify(String input) {
        if (input == null) {
            return "";
        }
        String hyphenated = NOT_SLUG_CHARS.matcher(VietnameseNormalizer.normalize(input)).replaceAll("-");
        return trimHyphens(hyphenated);
    }

    /** {@link #slugify(String)} cut to at most {@code maxLength} characters, never ending with a hyphen. */
    public static String slugify(String input, int maxLength) {
        if (maxLength < 1) {
            throw new IllegalArgumentException("maxLength must be positive");
        }
        String slug = slugify(input);
        return slug.length() <= maxLength ? slug : trimHyphens(slug.substring(0, maxLength));
    }

    private static String trimHyphens(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == '-') {
            start++;
        }
        while (end > start && value.charAt(end - 1) == '-') {
            end--;
        }
        return value.substring(start, end);
    }
}
