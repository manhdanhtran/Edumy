package com.edumy.learning.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import org.junit.jupiter.api.Test;

/**
 * UC-S20 / SRS §7 "Thành phần tạo PDF": Vietnamese with diacritics drawn and extractable, code and verification link
 * present, one landscape page, rendered well under NFR-05's 5 seconds.
 */
class OpenPdfCertificateRendererTest {

    static final CertificatePdfData DATA = new CertificatePdfData("EDU-7K3M-9QXA-2B4C", "Nguyễn Thị Ánh",
            "Lập trình Java Spring Boot từ cơ bản đến nâng cao", "Trần Văn Đức",
            Instant.parse("2026-11-15T20:30:00Z"), "https://app.edumy.vn/certificates/EDU-7K3M-9QXA-2B4C");

    final OpenPdfCertificateRenderer renderer = new OpenPdfCertificateRenderer();

    static String text(byte[] pdf) throws Exception {
        PdfReader reader = new PdfReader(pdf);
        try {
            StringBuilder all = new StringBuilder();
            PdfTextExtractor extractor = new PdfTextExtractor(reader);
            for (int page = 1; page <= reader.getNumberOfPages(); page++) {
                all.append(extractor.getTextFromPage(page)).append('\n');
            }
            return all.toString();
        } finally {
            reader.close();
        }
    }

    @Test
    void vietnameseTextCodeAndVerificationLink_extractable_onOneLandscapePage() throws Exception {
        byte[] pdf = renderer.render(DATA);

        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
        PdfReader reader = new PdfReader(pdf);
        try {
            assertThat(reader.getNumberOfPages()).isEqualTo(1);
            assertThat(reader.getPageSizeWithRotation(1).getWidth())
                    .isGreaterThan(reader.getPageSizeWithRotation(1).getHeight());
        } finally {
            reader.close();
        }
        assertThat(text(pdf))
                .contains("CHỨNG CHỈ HOÀN THÀNH KHÓA HỌC")
                .contains("Nguyễn Thị Ánh")
                .contains("Lập trình Java Spring Boot từ cơ bản đến nâng cao")
                .contains("Giảng viên: Trần Văn Đức")
                .contains("Ngày cấp: 16/11/2026") // 20:30 UTC = 03:30 the next day in Vietnam
                .contains("EDU-7K3M-9QXA-2B4C")
                .contains("https://app.edumy.vn/certificates/EDU-7K3M-9QXA-2B4C");
    }

    @Test
    void renders_underFiveSeconds_NFR05() {
        renderer.render(DATA); // warm-up (font subsetting classes)
        long start = System.nanoTime();
        for (int i = 0; i < 5; i++) {
            renderer.render(DATA);
        }
        Duration perPdf = Duration.ofNanos((System.nanoTime() - start) / 5);
        assertThat(perPdf).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void noInstructorName_lineLeftOut() throws Exception {
        CertificatePdfData withoutInstructor = new CertificatePdfData(DATA.certificateCode(), DATA.studentName(),
                DATA.courseTitle(), null, DATA.issuedAt(), DATA.verifyUrl());

        assertThat(text(renderer.render(withoutInstructor))).doesNotContain("Giảng viên:");
    }

    @Test
    void missingData_isRejectedBeforeRendering() {
        assertThatThrownBy(() -> new CertificatePdfData("EDU-1", null, "K", null, Instant.EPOCH, "u"))
                .isInstanceOf(NullPointerException.class);
    }
}
