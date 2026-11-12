package com.edumy.learning.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class EnrollmentTest {

    static final Instant NOW = Instant.parse("2026-11-12T08:00:00Z");
    static final Long STUDENT = 8L;
    static final Long COURSE = 3L;
    static final Long ORDER = 21L;

    @Test
    void free_noOrder_UCS15_BR09() {
        Enrollment enrollment = Enrollment.free(STUDENT, COURSE, NOW);

        assertThat(enrollment.getStudentId()).isEqualTo(STUDENT);
        assertThat(enrollment.getCourseId()).isEqualTo(COURSE);
        assertThat(enrollment.getOrderId()).isNull();
        assertThat(enrollment.getEnrollSource()).isEqualTo(EnrollmentSource.FREE);
        assertThat(enrollment.getEnrolledAt()).isEqualTo(NOW);
        assertThat(enrollment.isOwnedBy(STUDENT)).isTrue();
        assertThat(enrollment.isOwnedBy(9L)).isFalse();
    }

    @Test
    void purchase_withOrder_ckEnrollmentsSourceOrder_BR14() {
        Enrollment enrollment = Enrollment.purchase(STUDENT, COURSE, ORDER, NOW);

        assertThat(enrollment.getOrderId()).isEqualTo(ORDER);
        assertThat(enrollment.getEnrollSource()).isEqualTo(EnrollmentSource.PURCHASE);
        assertThat(enrollment.getEnrolledAt()).isEqualTo(NOW);
    }

    @Test
    void purchase_withoutOrder_rejected_BR14() {
        assertThatThrownBy(() -> Enrollment.purchase(STUDENT, COURSE, null, NOW))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullArguments_rejected() {
        assertThatThrownBy(() -> Enrollment.free(null, COURSE, NOW)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Enrollment.free(STUDENT, null, NOW)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Enrollment.free(STUDENT, COURSE, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Enrollment.purchase(STUDENT, COURSE, ORDER, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void equals_byIdOnly() {
        Enrollment a = Enrollment.free(STUDENT, COURSE, NOW);
        Enrollment b = Enrollment.free(STUDENT, COURSE, NOW);

        assertThat(a).isEqualTo(a).isNotEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a.toString()).contains("courseId=3");
    }
}
