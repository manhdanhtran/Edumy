package com.edumy.shared.text;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** P10 MINOR-4: LIKE wildcards typed by a user are escaped with {@code !} (query: {@code ESCAPE '!'}). */
class LikePatternTest {

    @Test
    void wildcardsAndEscapeCharacter_areEscaped() {
        assertThat(LikePattern.contains("%")).isEqualTo("%!%%");
        assertThat(LikePattern.contains("_")).isEqualTo("%!_%");
        assertThat(LikePattern.contains("50%_off!")).isEqualTo("%50!%!_off!!%");
    }

    @Test
    void plainKeyword_wrappedOnly() {
        assertThat(LikePattern.contains("nguyễn")).isEqualTo("%nguyễn%");
        assertThat(LikePattern.contains("a\\b")).isEqualTo("%a\\b%"); // backslash is no escape character here
    }
}
