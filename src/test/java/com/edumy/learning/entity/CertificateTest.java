package com.edumy.learning.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class CertificateTest {

    static final Instant NOW = Instant.parse("2026-11-12T08:00:00Z");
    static final String CODE = "EDU-7K2M-Q9XA-04BZ";

    static Certificate issue(String code, String student, String course, String instructor) {
        return Certificate.issue(Enrollment.free(8L, 3L, NOW), code, student, course, instructor, NOW);
    }

    @Test
    void issue_snapshot_BR39_BR40() {
        Enrollment enrollment = Enrollment.free(8L, 3L, NOW);
        Certificate certificate = Certificate.issue(enrollment, CODE, " Trần Thị Bình ", "Java cơ bản",
                "Nguyễn Văn An", NOW);

        assertThat(certificate.getEnrollment()).isSameAs(enrollment);
        assertThat(certificate.getCertificateCode()).isEqualTo(CODE);
        assertThat(certificate.getStudentName()).isEqualTo("Trần Thị Bình");
        assertThat(certificate.getCourseTitle()).isEqualTo("Java cơ bản");
        assertThat(certificate.getInstructorName()).isEqualTo("Nguyễn Văn An");
        assertThat(certificate.getIssuedAt()).isEqualTo(NOW);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "EDU-7K2M-Q9XA-04B", "EDU-7K2M-Q9XA-04BZZ", "edu-7k2m-q9xa-04bz", "EDU-7K2M-Q9XA-04Bz",
            "EDU-7K2M-Q9XA-04BZ\n", "XEDU-7K2M-Q9XA-04BZ", "EDU-7K2MQ-9XA-04BZ", "EDU-Đ K2M-Q9XA-04BZ"})
    void issue_invalidCode_rejected_ckCertificatesCode(String code) {
        assertThat(Certificate.isValidCode(code)).isFalse();
        assertThatThrownBy(() -> issue(code, "A", "B", "C")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void isValidCode_format() {
        assertThat(Certificate.isValidCode(CODE)).isTrue();
        assertThat(Certificate.isValidCode("EDU-0000-AAAA-9999")).isTrue();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void issue_blankSnapshot_rejected_BR40(String blank) {
        assertThatThrownBy(() -> issue(CODE, blank, "B", "C")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> issue(CODE, "A", blank, "C")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> issue(CODE, "A", "B", blank)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void issue_snapshotLengths_matchColumns() {
        Certificate max = issue(CODE, "n".repeat(100), "t".repeat(200), "i".repeat(100));
        assertThat(max.getStudentName()).hasSize(100);
        assertThat(max.getCourseTitle()).hasSize(200);
        assertThatThrownBy(() -> issue(CODE, "n".repeat(101), "B", "C")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> issue(CODE, "A", "t".repeat(201), "C")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> issue(CODE, "A", "B", "i".repeat(101))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void issue_nullArguments_rejected() {
        assertThatThrownBy(() -> Certificate.issue(null, CODE, "A", "B", "C", NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Certificate.issue(Enrollment.free(8L, 3L, NOW), CODE, "A", "B", "C", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void equals_byIdOnly() {
        Certificate a = issue(CODE, "A", "B", "C");
        Certificate b = issue(CODE, "A", "B", "C");

        assertThat(a).isEqualTo(a).isNotEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a.toString()).contains(CODE).doesNotContain("Enrollment");
    }
}
