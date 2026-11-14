package com.edumy.learning.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.edumy.learning.dto.response.CertificateResponse;
import org.junit.jupiter.api.Test;

class SampleCertificatePdfRendererTest {

    private final CertificateResponse certificate = new CertificateResponse(1L, "EDU-7K2M-9QXA-4F8T", 11L, 2L,
            "HTML và CSS cho người mới bắt đầu", "Đỗ Thị Hương", "Nguyễn Văn Hùng", Instant.parse("2026-08-06T13:00:00Z"));

    @Test
    void render_returnsAValidSinglePagePdf() {
        byte[] pdf = new SampleCertificatePdfRenderer().render(certificate);
        String text = new String(pdf, StandardCharsets.ISO_8859_1);

        assertThat(text).startsWith("%PDF-1.");
        assertThat(text.stripTrailing()).endsWith("%%EOF");
        assertThat(text).contains("SAMPLE CERTIFICATE - Edumy (phase 1 stub)").contains("/Type /Page ");

        // startxref points at the xref table and every entry points at "<n> 0 obj"
        Matcher start = Pattern.compile("startxref\\n(\\d+)\\n").matcher(text);
        assertThat(start.find()).isTrue();
        assertThat(text.startsWith("xref", Integer.parseInt(start.group(1)))).isTrue();
        Matcher entry = Pattern.compile("(\\d{10}) 00000 n").matcher(text);
        int entries = 0;
        while (entry.find()) {
            entries++;
            assertThat(text.substring(Integer.parseInt(entry.group(1)))).matches("(?s)\\d+ 0 obj.*");
        }
        assertThat(entries).isEqualTo(5);
    }

    @Test
    void render_returnsACopyEachTime() {
        SampleCertificatePdfRenderer renderer = new SampleCertificatePdfRenderer();
        byte[] first = renderer.render(certificate);
        first[0] = 0;
        assertThat(renderer.render(certificate)[0]).isEqualTo((byte) '%');
    }
}
