package com.edumy.catalog.repository;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** LIKE pattern of the UC-G02 keyword (docs/ai/03 §3.2): wildcards typed by the user are matched literally. */
class CourseCatalogRepositoryTest {

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(delimiter = ';', textBlock = """
            lap trinh;    %lap trinh%
            100%;         %100!%%
            a_b;          %a!_b%
            wow!;         %wow!!%
            !%_;          %!!!%!_%
            """)
    void likeContains_escapesWildcardsAndEscapeChar(String keyword, String pattern) {
        assertThat(CourseCatalogRepository.likeContains(keyword)).isEqualTo(pattern);
    }
}
