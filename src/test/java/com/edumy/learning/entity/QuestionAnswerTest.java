package com.edumy.learning.entity;

import static com.edumy.learning.entity.CourseQuestionTest.assertConflict;
import static com.edumy.learning.entity.CourseQuestionTest.assertValidationFailed;
import static com.edumy.learning.entity.CourseQuestionTest.questionIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class QuestionAnswerTest {

    static final Instant NOW = Instant.parse("2026-11-12T08:00:00Z");
    static final Instant LATER = NOW.plusSeconds(600);
    static final Long INSTRUCTOR = 5L;
    static final Long ADMIN = 1L;

    static QuestionAnswer newAnswer() {
        return QuestionAnswer.post(CourseQuestionTest.newQuestion(), INSTRUCTOR, AuthorType.INSTRUCTOR,
                "Cài JDK 21 nhé", NOW);
    }

    /** An answer driven through the real transitions into {@code status}. */
    static QuestionAnswer answerIn(ContentStatus status) {
        QuestionAnswer answer = newAnswer();
        switch (status) {
            case VISIBLE -> { }
            case DELETED -> answer.delete();
            case HIDDEN -> answer.hide(ADMIN, NOW);
        }
        assertThat(answer.getVisibilityStatus()).isEqualTo(status);
        return answer;
    }

    // --------------------------------------------------------------------------------------------------------- post

    @ParameterizedTest
    @EnumSource(AuthorType.class)
    void post_visibleQuestion_visibleAnswer_trimmed_UCI21(AuthorType type) {
        CourseQuestion question = CourseQuestionTest.newQuestion();

        QuestionAnswer answer = QuestionAnswer.post(question, INSTRUCTOR, type, "  Trả lời  ", NOW);

        assertThat(answer.getQuestion()).isSameAs(question);
        assertThat(answer.getAuthorId()).isEqualTo(INSTRUCTOR);
        assertThat(answer.getAuthorType()).isEqualTo(type);
        assertThat(answer.getContent()).isEqualTo("Trả lời");
        assertThat(answer.getVisibilityStatus()).isEqualTo(ContentStatus.VISIBLE);
        assertThat(answer.isVisible()).isTrue();
        assertThat(answer.getModeratedBy()).isNull();
        assertThat(answer.getModeratedAt()).isNull();
        assertThat(answer.getCreatedAt()).isEqualTo(NOW);
        assertThat(answer.isAuthoredBy(INSTRUCTOR)).isTrue();
        assertThat(answer.isAuthoredBy(9L)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = ContentStatus.class, names = "VISIBLE", mode = EnumSource.Mode.EXCLUDE)
    void post_deletedOrHiddenQuestion_rejected_conflict(ContentStatus status) {
        CourseQuestion question = questionIn(status);

        CourseQuestionTest.assertRejected(status,
                () -> QuestionAnswer.post(question, INSTRUCTOR, AuthorType.INSTRUCTOR, "Trả lời", NOW));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "  ", "\t"})
    void post_blankContent_rejected_D44(String blank) {
        CourseQuestion question = CourseQuestionTest.newQuestion();

        assertValidationFailed(() -> QuestionAnswer.post(question, INSTRUCTOR, AuthorType.INSTRUCTOR, blank, NOW),
                "content");
    }

    @Test
    void post_contentLength_max5000_D44() {
        CourseQuestion question = CourseQuestionTest.newQuestion();
        assertThat(QuestionAnswer.post(question, INSTRUCTOR, AuthorType.STUDENT, "đ".repeat(5000), NOW).getContent())
                .hasSize(5000);
        assertValidationFailed(() -> QuestionAnswer.post(question, INSTRUCTOR, AuthorType.STUDENT,
                "đ".repeat(5001), NOW), "content");
    }

    @Test
    void post_nullArguments_rejected() {
        CourseQuestion question = CourseQuestionTest.newQuestion();
        assertThatThrownBy(() -> QuestionAnswer.post(null, INSTRUCTOR, AuthorType.INSTRUCTOR, "C", NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> QuestionAnswer.post(question, null, AuthorType.INSTRUCTOR, "C", NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> QuestionAnswer.post(question, INSTRUCTOR, null, "C", NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> QuestionAnswer.post(question, INSTRUCTOR, AuthorType.INSTRUCTOR, "C", null))
                .isInstanceOf(NullPointerException.class);
    }

    // --------------------------------------------------------------------------------------------------------- edit

    @Test
    void edit_visible_updated() {
        QuestionAnswer answer = newAnswer();

        answer.edit(" Sửa lại ");

        assertThat(answer.getContent()).isEqualTo("Sửa lại");
    }

    @ParameterizedTest
    @EnumSource(value = ContentStatus.class, names = "VISIBLE", mode = EnumSource.Mode.EXCLUDE)
    void edit_deletedOrHidden_rejected_conflict_BR41(ContentStatus status) {
        QuestionAnswer answer = answerIn(status);

        CourseQuestionTest.assertRejected(status, () -> answer.edit("Sửa"));
        assertThat(answer.getContent()).isEqualTo("Cài JDK 21 nhé");
    }

    @Test
    void edit_blank_rejected_unchanged() {
        QuestionAnswer answer = newAnswer();

        assertValidationFailed(() -> answer.edit(" "), "content");
        assertThat(answer.getContent()).isEqualTo("Cài JDK 21 nhé");
    }

    // ------------------------------------------------------------------------------------------------------- delete

    @Test
    void delete_visible_deleted() {
        QuestionAnswer answer = newAnswer();

        answer.delete();

        assertThat(answer.getVisibilityStatus()).isEqualTo(ContentStatus.DELETED);
        assertThat(answer.isVisible()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = ContentStatus.class, names = "VISIBLE", mode = EnumSource.Mode.EXCLUDE)
    void delete_deletedOrHidden_rejected_conflict(ContentStatus status) {
        QuestionAnswer answer = answerIn(status);

        CourseQuestionTest.assertRejected(status, answer::delete);
        assertThat(answer.getVisibilityStatus()).isEqualTo(status);
    }

    // ------------------------------------------------------------------------------------------------- hide/restore

    @Test
    void hide_visible_hiddenWithModeration_UCA19() {
        QuestionAnswer answer = newAnswer();

        answer.hide(ADMIN, LATER);

        assertThat(answer.getVisibilityStatus()).isEqualTo(ContentStatus.HIDDEN);
        assertThat(answer.getModeratedBy()).isEqualTo(ADMIN);
        assertThat(answer.getModeratedAt()).isEqualTo(LATER);
    }

    @ParameterizedTest
    @EnumSource(value = ContentStatus.class, names = "VISIBLE", mode = EnumSource.Mode.EXCLUDE)
    void hide_deletedOrHidden_rejected_conflict(ContentStatus status) {
        QuestionAnswer answer = answerIn(status);

        assertConflict(() -> answer.hide(2L, LATER));
        assertThat(answer.getVisibilityStatus()).isEqualTo(status);
    }

    @Test
    void restore_hidden_visible_BR35() {
        QuestionAnswer answer = answerIn(ContentStatus.HIDDEN);

        answer.restore(2L, LATER);

        assertThat(answer.getVisibilityStatus()).isEqualTo(ContentStatus.VISIBLE);
        assertThat(answer.getModeratedBy()).isEqualTo(2L);
        assertThat(answer.getModeratedAt()).isEqualTo(LATER);
    }

    @ParameterizedTest
    @EnumSource(value = ContentStatus.class, names = "HIDDEN", mode = EnumSource.Mode.EXCLUDE)
    void restore_notHidden_rejected_conflict(ContentStatus status) {
        QuestionAnswer answer = answerIn(status);

        assertConflict(() -> answer.restore(ADMIN, LATER));
        assertThat(answer.getVisibilityStatus()).isEqualTo(status);
    }

    @Test
    void moderation_nullArguments_rejected() {
        QuestionAnswer answer = newAnswer();
        assertThatThrownBy(() -> answer.hide(null, NOW)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> answer.hide(ADMIN, null)).isInstanceOf(NullPointerException.class);
        QuestionAnswer hidden = answerIn(ContentStatus.HIDDEN);
        assertThatThrownBy(() -> hidden.restore(null, NOW)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> hidden.restore(ADMIN, null)).isInstanceOf(NullPointerException.class);
        assertThat(answer.getVisibilityStatus()).isEqualTo(ContentStatus.VISIBLE);
    }

    @Test
    void equals_byIdOnly() {
        QuestionAnswer a = newAnswer();
        QuestionAnswer b = newAnswer();

        assertThat(a).isEqualTo(a).isNotEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a.toString()).contains("INSTRUCTOR").doesNotContain("CourseQuestion[");
    }
}
