package com.edumy.shared.text;

/**
 * LIKE patterns for keywords typed by a user: the wildcards of the keyword ({@code %}, {@code _}) are escaped, so they
 * match literally and {@code q=%} cannot list every row. The escape character is {@code !} (not the backslash): it
 * works whatever the {@code NO_BACKSLASH_ESCAPES} SQL mode is and needs no escaping inside Java / JPQL string
 * literals. Every query using such a pattern must say {@code LIKE :pattern ESCAPE '!'}.
 */
public final class LikePattern {

    /** The escape character of {@link #contains}; write {@code ESCAPE '!'} in the query. */
    public static final char ESCAPE = '!';

    private LikePattern() {
    }

    /** {@code %keyword%} with {@code !}, {@code %} and {@code _} of the keyword escaped. */
    public static String contains(String keyword) {
        String e = String.valueOf(ESCAPE);
        return "%" + keyword.replace(e, e + e).replace("%", e + "%").replace("_", e + "_") + "%";
    }
}
