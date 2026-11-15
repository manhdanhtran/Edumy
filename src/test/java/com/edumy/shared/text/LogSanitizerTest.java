package com.edumy.shared.text;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** P10 MINOR-3: free text in log lines (CWE-117 log injection). */
class LogSanitizerTest {

    @Test
    void crLfAndControlCharacters_becomeUnderscore_oneLine() {
        String forged = "Spam\r\n2026-11-15 INFO Payment success\tx" + (char) 27 + "[31m" + ' ' + "end";

        String clean = LogSanitizer.clean(forged);

        assertThat(clean).isEqualTo("Spam__2026-11-15 INFO Payment success_x_[31m_end");
        assertThat(clean.lines()).hasSize(1);
    }

    @Test
    void vietnameseText_keptAsIs() {
        assertThat(LogSanitizer.clean("Nội dung quảng cáo, vi phạm điều khoản")).isEqualTo(
                "Nội dung quảng cáo, vi phạm điều khoản");
    }

    @Test
    void longText_cutAt200_withMarker() {
        String clean = LogSanitizer.clean("a".repeat(250));

        assertThat(clean).startsWith("a".repeat(LogSanitizer.MAX_LENGTH)).endsWith("…(+50)");
        assertThat(clean).hasSize(LogSanitizer.MAX_LENGTH + "…(+50)".length());
        assertThat(LogSanitizer.clean("a".repeat(200))).isEqualTo("a".repeat(200));
    }

    @Test
    void nullAndEmpty() {
        assertThat(LogSanitizer.clean(null)).isEqualTo("-");
        assertThat(LogSanitizer.clean("")).isEmpty();
    }
}
