package com.edumy.learning.entity;

import static com.edumy.learning.entity.CourseQuestionTest.assertCode;
import static com.edumy.learning.entity.CourseQuestionTest.assertConflict;
import static com.edumy.learning.entity.CourseQuestionTest.assertValidationFailed;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import com.edumy.shared.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class CourseReviewTest {

    static final Instant NOW = Instant.parse("2026-11-12T08:00:00Z");
    static final Instant LATER = NOW.plusSeconds(600);
    static final Long ADMIN = 1L;

    static CourseReview newReview() {
        return CourseReview.create(Enrollment.free(8L, 3L, NOW), 4, "Khóa học hay", NOW);
    }

    /** A review driven through the real transitions into {@code status}. */
    static CourseReview reviewIn(ReviewStatus status) {
        CourseReview review = newReview();
        switch (status) {
            case VISIBLE -> { }
            case WITHDRAWN -> review.withdraw();
            case HIDDEN -> review.hide(ADMIN, NOW);
        }
        assertThat(review.getVisibilityStatus()).isEqualTo(status);
        return review;
    }

    // ------------------------------------------------------------------------------------------------------- create

    @Test
    void create_visible_trimmedComment_UCS12() {
        Enrollment enrollment = Enrollment.free(8L, 3L, NOW);
        CourseReview review = CourseReview.create(enrollment, 5, "  Rất hữu ích  ", NOW);

        assertThat(review.getEnrollment()).isSameAs(enrollment);
        assertThat(review.getRating()).isEqualTo((short) 5);
        assertThat(review.getComment()).isEqualTo("Rất hữu ích");
        assertThat(review.getVisibilityStatus()).isEqualTo(ReviewStatus.VISIBLE);
        assertThat(review.isVisible()).isTrue();
        assertThat(review.getModeratedBy()).isNull();
        assertThat(review.getModeratedAt()).isNull();
        assertThat(review.getCreatedAt()).isEqualTo(NOW);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\n\t"})
    void create_blankComment_null_D35(String comment) {
        assertThat(CourseReview.create(Enrollment.free(8L, 3L, NOW), 3, comment, NOW).getComment()).isNull();
        assertThat(CourseReview.create(Enrollment.free(8L, 3L, NOW), 3, null, NOW).getComment()).isNull();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 5})
    void create_ratingBounds_accepted_BR25(int rating) {
        assertThat(CourseReview.create(Enrollment.free(8L, 3L, NOW), rating, null, NOW).getRating())
                .isEqualTo((short) rating);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 6, -1, 32768})
    void create_ratingOutOfRange_rejected_BR25(int rating) {
        assertValidationFailed(() -> CourseReview.create(Enrollment.free(8L, 3L, NOW), rating, null, NOW), "rating");
    }

    @Test
    void create_commentLength_max2000_D35() {
        Enrollment enrollment = Enrollment.free(8L, 3L, NOW);
        assertThat(CourseReview.create(enrollment, 4, "ă".repeat(2000), NOW).getComment()).hasSize(2000);
        assertValidationFailed(() -> CourseReview.create(enrollment, 4, "a".repeat(2001), NOW), "comment");
    }

    @Test
    void create_nullArguments_rejected() {
        assertThatThrownBy(() -> CourseReview.create(null, 4, null, NOW)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> CourseReview.create(Enrollment.free(8L, 3L, NOW), 4, null, null))
                .isInstanceOf(NullPointerException.class);
    }

    // ------------------------------------------------------------------------------------------------------- update

    @Test
    void update_visible_staysVisible_UCS13() {
        CourseReview review = newReview();

        review.update(2, " Chưa ổn ");

        assertThat(review.getRating()).isEqualTo((short) 2);
        assertThat(review.getComment()).isEqualTo("Chưa ổn");
        assertThat(review.getVisibilityStatus()).isEqualTo(ReviewStatus.VISIBLE);
    }

    @Test
    void update_withdrawn_reRate_visibleAgain() {
        CourseReview review = reviewIn(ReviewStatus.WITHDRAWN);

        review.update(5, null);

        assertThat(review.getVisibilityStatus()).isEqualTo(ReviewStatus.VISIBLE);
        assertThat(review.getRating()).isEqualTo((short) 5);
        assertThat(review.getComment()).isNull();
    }

    @Test
    void update_hidden_rejected_reviewHiddenByAdmin_BR35_BR41() {
        CourseReview review = reviewIn(ReviewStatus.HIDDEN);

        assertCode(() -> review.update(5, "Sửa lại"), ErrorCode.REVIEW_HIDDEN_BY_ADMIN);
        assertThat(review.getVisibilityStatus()).isEqualTo(ReviewStatus.HIDDEN);
        assertThat(review.getRating()).isEqualTo((short) 4);
        assertThat(review.getComment()).isEqualTo("Khóa học hay");
    }

    @Test
    void update_invalidInput_rejected_unchanged() {
        CourseReview review = reviewIn(ReviewStatus.WITHDRAWN);

        assertValidationFailed(() -> review.update(0, "x"), "rating");
        assertValidationFailed(() -> review.update(3, "x".repeat(2001)), "comment");
        assertThat(review.getRating()).isEqualTo((short) 4);
        assertThat(review.getVisibilityStatus()).isEqualTo(ReviewStatus.WITHDRAWN);
    }

    @Test
    void edit_visible_staysVisible_UCS13() {
        CourseReview review = newReview();

        review.edit(1, "Chưa như kỳ vọng");

        assertThat(review.getRating()).isEqualTo((short) 1);
        assertThat(review.getComment()).isEqualTo("Chưa như kỳ vọng");
        assertThat(review.getVisibilityStatus()).isEqualTo(ReviewStatus.VISIBLE);
    }

    @Test
    void edit_hidden_reviewHiddenByAdmin_withdrawn_conflict_unchanged() {
        CourseReview hidden = reviewIn(ReviewStatus.HIDDEN);
        CourseReview withdrawn = reviewIn(ReviewStatus.WITHDRAWN);

        assertCode(() -> hidden.edit(5, "x"), ErrorCode.REVIEW_HIDDEN_BY_ADMIN);
        assertConflict(() -> withdrawn.edit(5, "x"));
        assertThat(hidden.getRating()).isEqualTo((short) 4);
        assertThat(withdrawn.getRating()).isEqualTo((short) 4);
        assertThat(withdrawn.getVisibilityStatus()).isEqualTo(ReviewStatus.WITHDRAWN);
    }

    // ----------------------------------------------------------------------------------------------------- withdraw

    @Test
    void withdraw_visible_withdrawn_UCS14() {
        CourseReview review = newReview();

        review.withdraw();

        assertThat(review.getVisibilityStatus()).isEqualTo(ReviewStatus.WITHDRAWN);
        assertThat(review.isVisible()).isFalse();
    }

    @Test
    void withdraw_withdrawn_rejected_conflict() {
        CourseReview review = reviewIn(ReviewStatus.WITHDRAWN);

        assertConflict(review::withdraw);
    }

    @Test
    void withdraw_hidden_rejected_reviewHiddenByAdmin_BR41() {
        CourseReview review = reviewIn(ReviewStatus.HIDDEN);

        assertCode(review::withdraw, ErrorCode.REVIEW_HIDDEN_BY_ADMIN);
        assertThat(review.getVisibilityStatus()).isEqualTo(ReviewStatus.HIDDEN);
    }

    // -------------------------------------------------------------------------------------------------- hide/restore

    @ParameterizedTest
    @EnumSource(value = ReviewStatus.class, names = {"VISIBLE", "WITHDRAWN"})
    void hide_visibleOrWithdrawn_hiddenWithModeration_UCA15(ReviewStatus status) {
        CourseReview review = reviewIn(status);

        review.hide(ADMIN, LATER);

        assertThat(review.getVisibilityStatus()).isEqualTo(ReviewStatus.HIDDEN);
        assertThat(review.getModeratedBy()).isEqualTo(ADMIN);
        assertThat(review.getModeratedAt()).isEqualTo(LATER);
    }

    @Test
    void hide_hidden_rejected_conflict() {
        CourseReview review = reviewIn(ReviewStatus.HIDDEN);

        assertConflict(() -> review.hide(2L, LATER));
        assertThat(review.getModeratedBy()).isEqualTo(ADMIN);
        assertThat(review.getModeratedAt()).isEqualTo(NOW);
    }

    @Test
    void restore_hidden_visible_moderationPairKept_UCA17_BR35() {
        CourseReview review = reviewIn(ReviewStatus.HIDDEN);

        review.restore(2L, LATER);

        assertThat(review.getVisibilityStatus()).isEqualTo(ReviewStatus.VISIBLE);
        assertThat(review.getModeratedBy()).isEqualTo(2L);
        assertThat(review.getModeratedAt()).isEqualTo(LATER);
    }

    @ParameterizedTest
    @EnumSource(value = ReviewStatus.class, names = "HIDDEN", mode = EnumSource.Mode.EXCLUDE)
    void restore_notHidden_rejected_conflict(ReviewStatus status) {
        CourseReview review = reviewIn(status);

        assertConflict(() -> review.restore(ADMIN, LATER));
        assertThat(review.getVisibilityStatus()).isEqualTo(status);
        assertThat(review.getModeratedBy()).isNull();
    }

    @Test
    void moderation_nullArguments_rejected() {
        CourseReview review = newReview();
        assertThatThrownBy(() -> review.hide(null, NOW)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> review.hide(ADMIN, null)).isInstanceOf(NullPointerException.class);
        CourseReview hidden = reviewIn(ReviewStatus.HIDDEN);
        assertThatThrownBy(() -> hidden.restore(null, NOW)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> hidden.restore(ADMIN, null)).isInstanceOf(NullPointerException.class);
        assertThat(review.getVisibilityStatus()).isEqualTo(ReviewStatus.VISIBLE);
        assertThat(hidden.getVisibilityStatus()).isEqualTo(ReviewStatus.HIDDEN);
    }

    @Test
    void equals_byIdOnly() {
        CourseReview a = newReview();
        CourseReview b = newReview();

        assertThat(a).isEqualTo(a).isNotEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a.toString()).contains("rating=4").doesNotContain("Enrollment");
    }
}
