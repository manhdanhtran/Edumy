package com.edumy.shared.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class SlugGeneratorTest {

    /** ck_categories_slug / ck_courses_slug of V2 (the DB anchors with \z, {@code matches()} anchors both ends). */
    private static final Pattern DB_SLUG = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Lập trình Java & Spring     | lap-trinh-java-spring",
            "Đồ họa / Thiết kế           | do-hoa-thiet-ke",
            "  --Tiếng Việt cơ bản!!--   | tieng-viet-co-ban",
            "C++ cho người mới (2026)    | c-cho-nguoi-moi-2026",
            "Spring Boot 4.1             | spring-boot-4-1",
            "ỨNG DỤNG   web_api          | ung-dung-web-api",
            "'Lập\ttrình\nJava'          | lap-trinh-java",
    })
    void slugify_normalizesVietnameseAndHyphenates(String input, String expected) {
        String slug = SlugGenerator.slugify(input);
        assertThat(slug).isEqualTo(expected);
        assertThat(DB_SLUG.matcher(slug).matches()).as("matches the DDD slug CHECK").isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "!!!", "—", "日本語"})
    void slugify_withoutLetterOrDigit_isEmpty(String input) {
        assertThat(SlugGenerator.slugify(input)).isEmpty();
    }

    @Test
    void slugify_null_isEmpty() {
        assertThat(SlugGenerator.slugify(null)).isEmpty();
    }

    @Test
    void slugify_precomposedAndDecomposedInputGiveSameSlug() {
        String precomposed = "Đồ họa nâng cao";
        String decomposed = java.text.Normalizer.normalize(precomposed, java.text.Normalizer.Form.NFD);
        assertThat(SlugGenerator.slugify(decomposed)).isEqualTo(SlugGenerator.slugify(precomposed));
    }

    @Test
    void slugify_maxLength_cutsWithoutTrailingHyphen() {
        assertThat(SlugGenerator.slugify("Lập trình Java", 10)).isEqualTo("lap-trinh");
        assertThat(SlugGenerator.slugify("Lập trình Java", 9)).isEqualTo("lap-trinh");
        assertThat(SlugGenerator.slugify("Lập trình Java", 100)).isEqualTo("lap-trinh-java");
        assertThat(SlugGenerator.slugify("ab", 1)).isEqualTo("a");
    }

    @Test
    void slugify_maxLengthNotPositive_rejected() {
        assertThatThrownBy(() -> SlugGenerator.slugify("abc", 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
