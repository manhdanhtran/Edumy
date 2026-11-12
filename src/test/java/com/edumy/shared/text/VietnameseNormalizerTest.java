package com.edumy.shared.text;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

class VietnameseNormalizerTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Lập trình Đồ họa        | lap trinh do hoa",
            "ĐẶNG   Văn  Đức         | dang van duc",
            "Tiếng Việt cơ bản       | tieng viet co ban",
            "Ứng dụng ỨNG DỤNG       | ung dung ung dung",
            "Spring Boot 4           | spring boot 4",
            "'  Lập\ttrình\n Java  ' | lap trinh java",
    })
    void normalize_stripsAccentsFoldsDAndCollapsesSpaces(String input, String expected) {
        assertThat(VietnameseNormalizer.normalize(input)).isEqualTo(expected);
    }

    @Test
    void normalize_precomposedAndDecomposedInputGiveSameResult() {
        String precomposed = "Đồ họa";
        String decomposed = java.text.Normalizer.normalize(precomposed, java.text.Normalizer.Form.NFD);
        assertThat(VietnameseNormalizer.normalize(decomposed)).isEqualTo(VietnameseNormalizer.normalize(precomposed));
    }

    @Test
    void normalize_null_returnsNull() {
        assertThat(VietnameseNormalizer.normalize(null)).isNull();
    }

    @Test
    void normalize_blank_returnsEmpty() {
        assertThat(VietnameseNormalizer.normalize("   ")).isEmpty();
    }
}
