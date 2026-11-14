package com.edumy.learning.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import com.edumy.shared.error.BusinessException;
import com.edumy.shared.error.ErrorCode;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class CourseQuestionTest {

    static final Instant NOW = Instant.parse("2026-11-12T08:00:00Z");
    static final Instant LATER = NOW.plusSeconds(600);
    static final Long STUDENT = 8L;
    static final Long ADMIN = 1L;
    static final Long LESSON = 41L;

    static CourseQuestion newQuestion() {
        return CourseQuestion.ask(Enrollment.free(STUDENT, 3L, NOW), LESSON, "Lỗi cài đặt", "Em không cài được JDK",
                NOW);
    }

    /** A question driven through the real transitions into {@code status}. */
    static CourseQuestion questionIn(ContentStatus status) {
        CourseQuestion question = newQuestion();
        switch (status) {
            case VISIBLE -> { }
            case DELETED -> question.delete();
            case HIDDEN -> question.hide(ADMIN, NOW);
        }
        assertThat(question.getVisibilityStatus()).isEqualTo(status);
        return question;
    }

    // ---------------------------------------------------------------------------------------------------------- ask

    @Test
    void ask_visibleUnresolved_trimmed_UCS17_D44() {
        Enrollment enrollment = Enrollment.free(STUDENT, 3L, NOW);
        CourseQuestion question = CourseQuestion.ask(enrollment, LESSON, "  Tiêu đề ", "\tNội dung  ", NOW);

        assertThat(question.getEnrollment()).isSameAs(enrollment);
        assertThat(question.getLessonId()).isEqualTo(LESSON);
        assertThat(question.getTitle()).isEqualTo("Tiêu đề");
        assertThat(question.getContent()).isEqualTo("Nội dung");
        assertThat(question.isResolved()).isFalse();
        assertThat(question.getVisibilityStatus()).isEqualTo(ContentStatus.VISIBLE);
        assertThat(question.isVisible()).isTrue();
        assertThat(question.getModeratedBy()).isNull();
        assertThat(question.getModeratedAt()).isNull();
        assertThat(question.getCreatedAt()).isEqualTo(NOW);
        assertThat(question.isAskedBy(STUDENT)).isTrue();
        assertThat(question.isAskedBy(9L)).isFalse();
    }

    @Test
    void ask_withoutLesson_courseLevelQuestion() {
        CourseQuestion question = CourseQuestion.ask(Enrollment.free(STUDENT, 3L, NOW), null, "T", "C", NOW);

        assertThat(question.getLessonId()).isNull();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "\n"})
    void ask_blankTitleOrContent_rejected_D44(String blank) {
        Enrollment enrollment = Enrollment.free(STUDENT, 3L, NOW);
        assertValidationFailed(() -> CourseQuestion.ask(enrollment, null, blank, "C", NOW), "title");
        assertValidationFailed(() -> CourseQuestion.ask(enrollment, null, "T", blank, NOW), "content");
    }

    @Test
    void ask_lengthLimits_title200_content5000_D44() {
        Enrollment enrollment = Enrollment.free(STUDENT, 3L, NOW);
        CourseQuestion max = CourseQuestion.ask(enrollment, null, "ê".repeat(200), "ê".repeat(5000), NOW);
        assertThat(max.getTitle()).hasSize(200);
        assertThat(max.getContent()).hasSize(5000);
        // MySQL counts characters (code points): one emoji is one character of VARCHAR(200)
        assertThat(CourseQuestion.ask(enrollment, null, "😀".repeat(200), "C", NOW).getTitle()).hasSize(400);
        assertValidationFailed(() -> CourseQuestion.ask(enrollment, null, "t".repeat(201), "C", NOW), "title");
        assertValidationFailed(() -> CourseQuestion.ask(enrollment, null, "T", "c".repeat(5001), NOW), "content");
    }

    @Test
    void ask_nullArguments_rejected() {
        assertThatThrownBy(() -> CourseQuestion.ask(null, null, "T", "C", NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> CourseQuestion.ask(Enrollment.free(STUDENT, 3L, NOW), null, "T", "C", null))
                .isInstanceOf(NullPointerException.class);
    }

    // --------------------------------------------------------------------------------------------------------- edit

    @Test
    void edit_visible_updated_UCS19() {
        CourseQuestion question = newQuestion();

        question.edit(" Tiêu đề mới ", " Nội dung mới ");

        assertThat(question.getTitle()).isEqualTo("Tiêu đề mới");
        assertThat(question.getContent()).isEqualTo("Nội dung mới");
        assertThat(question.getVisibilityStatus()).isEqualTo(ContentStatus.VISIBLE);
    }

    @ParameterizedTest
    @EnumSource(value = ContentStatus.class, names = "VISIBLE", mode = EnumSource.Mode.EXCLUDE)
    void edit_deletedOrHidden_rejected_conflict_BR41(ContentStatus status) {
        CourseQuestion question = questionIn(status);

        assertRejected(status, () -> question.edit("Mới", "Mới"));
        assertThat(question.getTitle()).isEqualTo("Lỗi cài đặt");
    }

    @Test
    void edit_invalidInput_rejected_unchanged() {
        CourseQuestion question = newQuestion();

        assertValidationFailed(() -> question.edit(" ", "Mới"), "title");
        assertValidationFailed(() -> question.edit("Mới", "c".repeat(5001)), "content");
        assertThat(question.getTitle()).isEqualTo("Lỗi cài đặt");
        assertThat(question.getContent()).isEqualTo("Em không cài được JDK");
    }

    // ---------------------------------------------------------------------------------------------------- resolve

    @Test
    void markResolved_visible_resolved_idempotent() {
        CourseQuestion question = newQuestion();

        question.markResolved();
        question.markResolved();

        assertThat(question.isResolved()).isTrue();
        assertThat(question.getVisibilityStatus()).isEqualTo(ContentStatus.VISIBLE);
    }

    @Test
    void markUnresolved_resolved_reopened_idempotent_UCS19() {
        CourseQuestion question = newQuestion();
        question.markResolved();

        question.markUnresolved();
        question.markUnresolved();

        assertThat(question.isResolved()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = ContentStatus.class, names = "VISIBLE", mode = EnumSource.Mode.EXCLUDE)
    void markUnresolved_deletedOrHidden_rejected(ContentStatus status) {
        CourseQuestion question = questionIn(status);

        assertRejected(status, question::markUnresolved);
    }

    @ParameterizedTest
    @EnumSource(value = ContentStatus.class, names = "VISIBLE", mode = EnumSource.Mode.EXCLUDE)
    void markResolved_deletedOrHidden_rejected_conflict(ContentStatus status) {
        CourseQuestion question = questionIn(status);

        assertRejected(status, question::markResolved);
        assertThat(question.isResolved()).isFalse();
    }

    // ------------------------------------------------------------------------------------------------------- delete

    @Test
    void delete_visible_deleted() {
        CourseQuestion question = newQuestion();

        question.delete();

        assertThat(question.getVisibilityStatus()).isEqualTo(ContentStatus.DELETED);
        assertThat(question.isVisible()).isFalse();
        assertThat(question.getModeratedBy()).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = ContentStatus.class, names = "VISIBLE", mode = EnumSource.Mode.EXCLUDE)
    void delete_deletedOrHidden_rejected_conflict(ContentStatus status) {
        CourseQuestion question = questionIn(status);

        assertRejected(status, question::delete);
        assertThat(question.getVisibilityStatus()).isEqualTo(status);
    }

    // ------------------------------------------------------------------------------------------------- hide/restore

    @Test
    void hide_visible_hiddenWithModeration_UCA19() {
        CourseQuestion question = newQuestion();

        question.hide(ADMIN, LATER);

        assertThat(question.getVisibilityStatus()).isEqualTo(ContentStatus.HIDDEN);
        assertThat(question.getModeratedBy()).isEqualTo(ADMIN);
        assertThat(question.getModeratedAt()).isEqualTo(LATER);
    }

    @ParameterizedTest
    @EnumSource(value = ContentStatus.class, names = "VISIBLE", mode = EnumSource.Mode.EXCLUDE)
    void hide_deletedOrHidden_rejected_conflict(ContentStatus status) {
        CourseQuestion question = questionIn(status);

        assertConflict(() -> question.hide(2L, LATER));
        assertThat(question.getVisibilityStatus()).isEqualTo(status);
    }

    @Test
    void restore_hidden_visible_moderationPairKept_BR35() {
        CourseQuestion question = questionIn(ContentStatus.HIDDEN);

        question.restore(2L, LATER);

        assertThat(question.getVisibilityStatus()).isEqualTo(ContentStatus.VISIBLE);
        assertThat(question.getModeratedBy()).isEqualTo(2L);
        assertThat(question.getModeratedAt()).isEqualTo(LATER);
        question.edit("Lại sửa được", "Sau khi khôi phục");
    }

    @ParameterizedTest
    @EnumSource(value = ContentStatus.class, names = "HIDDEN", mode = EnumSource.Mode.EXCLUDE)
    void restore_notHidden_rejected_conflict_deletedIsFinal(ContentStatus status) {
        CourseQuestion question = questionIn(status);

        assertConflict(() -> question.restore(ADMIN, LATER));
        assertThat(question.getVisibilityStatus()).isEqualTo(status);
    }

    @Test
    void moderation_nullArguments_rejected() {
        CourseQuestion question = newQuestion();
        assertThatThrownBy(() -> question.hide(null, NOW)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> question.hide(ADMIN, null)).isInstanceOf(NullPointerException.class);
        CourseQuestion hidden = questionIn(ContentStatus.HIDDEN);
        assertThatThrownBy(() -> hidden.restore(null, NOW)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> hidden.restore(ADMIN, null)).isInstanceOf(NullPointerException.class);
        assertThat(question.getVisibilityStatus()).isEqualTo(ContentStatus.VISIBLE);
    }

    @Test
    void equals_byIdOnly() {
        CourseQuestion a = newQuestion();
        CourseQuestion b = newQuestion();

        assertThat(a).isEqualTo(a).isNotEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a.toString()).contains("VISIBLE").doesNotContain("Enrollment");
    }

    // ------------------------------------------------------------------------------------------------------ helpers

    /** What the asker gets on a question that is not visible: hidden → BR-41 code, deleted → conflict. */
    static void assertRejected(ContentStatus status, ThrowingCallable call) {
        assertCode(call, status == ContentStatus.HIDDEN ? ErrorCode.QA_CONTENT_HIDDEN_BY_ADMIN
                : ErrorCode.DATA_CONFLICT);
    }

    static void assertConflict(ThrowingCallable call) {
        assertCode(call, ErrorCode.DATA_CONFLICT);
    }

    static void assertCode(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getCode()).isEqualTo(code));
    }

    static void assertValidationFailed(ThrowingCallable call, String field) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class, e -> {
            assertThat(e.getCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(e.getErrors()).singleElement().satisfies(v -> {
                assertThat(v.field()).isEqualTo(field);
                assertThat(v.message()).isNotBlank();
            });
        });
    }
}
