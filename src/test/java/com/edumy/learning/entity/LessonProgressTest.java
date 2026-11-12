package com.edumy.learning.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class LessonProgressTest {

    static final Instant NOW = Instant.parse("2026-11-12T08:00:00Z");
    static final Instant LATER = NOW.plusSeconds(600);
    static final Long LESSON = 41L;

    static LessonProgress newProgress() {
        return LessonProgress.start(Enrollment.free(8L, 3L, NOW), LESSON, NOW);
    }

    @Test
    void start_notCompleted_accessedNow_D34() {
        Enrollment enrollment = Enrollment.free(8L, 3L, NOW);
        LessonProgress progress = LessonProgress.start(enrollment, LESSON, NOW);

        assertThat(progress.getEnrollment()).isSameAs(enrollment);
        assertThat(progress.getLessonId()).isEqualTo(LESSON);
        assertThat(progress.isCompleted()).isFalse();
        assertThat(progress.getCompletedAt()).isNull();
        assertThat(progress.getLastAccessedAt()).isEqualTo(NOW);
    }

    @Test
    void start_nullArguments_rejected() {
        Enrollment enrollment = Enrollment.free(8L, 3L, NOW);
        assertThatThrownBy(() -> LessonProgress.start(null, LESSON, NOW)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> LessonProgress.start(enrollment, null, NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> LessonProgress.start(enrollment, LESSON, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void visit_updatesLastAccessedOnly_D34() {
        LessonProgress progress = newProgress();

        progress.visit(LATER);

        assertThat(progress.getLastAccessedAt()).isEqualTo(LATER);
        assertThat(progress.isCompleted()).isFalse();
        assertThatThrownBy(() -> progress.visit(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void complete_setsFlagAndTimeTogether_ckLessonProgressesCompleted_D33() {
        LessonProgress progress = newProgress();

        progress.complete(LATER);

        assertThat(progress.isCompleted()).isTrue();
        assertThat(progress.getCompletedAt()).isEqualTo(LATER);
        assertThat(progress.getLastAccessedAt()).isEqualTo(NOW);
    }

    @Test
    void complete_twice_idempotent_keepsFirstCompletedAt() {
        LessonProgress progress = newProgress();
        progress.complete(NOW);

        progress.complete(LATER);

        assertThat(progress.isCompleted()).isTrue();
        assertThat(progress.getCompletedAt()).isEqualTo(NOW);
    }

    @Test
    void complete_nullNow_rejected() {
        LessonProgress progress = newProgress();
        assertThatThrownBy(() -> progress.complete(null)).isInstanceOf(NullPointerException.class);
        assertThat(progress.isCompleted()).isFalse();
    }

    @Test
    void uncomplete_clearsFlagAndTimeTogether_D33() {
        LessonProgress progress = newProgress();
        progress.complete(NOW);

        progress.uncomplete();

        assertThat(progress.isCompleted()).isFalse();
        assertThat(progress.getCompletedAt()).isNull();
    }

    @Test
    void uncomplete_notCompleted_idempotent() {
        LessonProgress progress = newProgress();

        progress.uncomplete();

        assertThat(progress.isCompleted()).isFalse();
        assertThat(progress.getCompletedAt()).isNull();
    }

    @Test
    void completeAgainAfterUncomplete_newCompletedAt() {
        LessonProgress progress = newProgress();
        progress.complete(NOW);
        progress.uncomplete();

        progress.complete(LATER);

        assertThat(progress.getCompletedAt()).isEqualTo(LATER);
    }

    @Test
    void equals_byIdOnly() {
        LessonProgress a = newProgress();
        LessonProgress b = newProgress();

        assertThat(a).isEqualTo(a).isNotEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a.toString()).contains("lessonId=41").doesNotContain("Enrollment");
    }
}
