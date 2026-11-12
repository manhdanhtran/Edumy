package com.edumy.authoring.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import com.edumy.shared.error.BusinessException;
import com.edumy.shared.error.ErrorCode;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class CourseTest {

    static final Instant NOW = Instant.parse("2026-11-12T08:00:00Z");
    static final Long INSTRUCTOR = 10L;
    static final Long ADMIN = 1L;

    static Category category() {
        return Category.create("Lập trình", "lap-trinh", NOW);
    }

    static Course newCourse() {
        return Course.create(INSTRUCTOR, category(), "Lập trình Java cơ bản", "lap-trinh-java-co-ban", "Mô tả",
                "https://cdn.edumy.vn/t.png", 499_000, NOW);
    }

    /** A course driven through the real transitions into {@code status}. */
    static Course courseIn(CourseStatus status) {
        Course course = newCourse();
        switch (status) {
            case DRAFT -> { }
            case PENDING_REVIEW -> course.submitForReview(NOW);
            case REJECTED -> {
                course.submitForReview(NOW);
                course.reject(ADMIN, "Thiếu mô tả", NOW);
            }
            case PUBLISHED -> {
                course.submitForReview(NOW);
                course.approve(ADMIN, NOW);
            }
            case HIDDEN -> {
                course.submitForReview(NOW);
                course.approve(ADMIN, NOW);
                course.hide(ADMIN, "Vi phạm", NOW);
            }
        }
        assertThat(course.getStatus()).isEqualTo(status);
        return course;
    }

    // ---------------------------------------------------------------------------------------------------------- create

    @Test
    void create_validInput_draftWithNormalizedTitle_UCI01() {
        Category category = category();
        Course course = Course.create(INSTRUCTOR, category, "  Lập Trình  Đồ họa ", "lap-trinh-do-hoa", "  ", " ", 0,
                NOW);

        assertThat(course.getMainInstructorId()).isEqualTo(INSTRUCTOR);
        assertThat(course.getCategory()).isSameAs(category);
        assertThat(course.getTitle()).isEqualTo("Lập Trình  Đồ họa");
        assertThat(course.getTitleNormalized()).isEqualTo("lap trinh do hoa");
        assertThat(course.getSlug()).isEqualTo("lap-trinh-do-hoa");
        assertThat(course.getDescription()).isNull();
        assertThat(course.getThumbnailUrl()).isNull();
        assertThat(course.getPrice()).isZero();
        assertThat(course.getStatus()).isEqualTo(CourseStatus.DRAFT);
        assertThat(course.getCreatedAt()).isEqualTo(NOW);
        assertThat(course.getSubmittedAt()).isNull();
        assertThat(course.getReviewedBy()).isNull();
        assertThat(course.getPublishedAt()).isNull();
        assertThat(course.getUpdatedAt()).isNull();
        assertThat(course.isEditable()).isTrue();
        assertThat(course.isMainInstructor(INSTRUCTOR)).isTrue();
        assertThat(course.isMainInstructor(99L)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 10_000, 11_000, 499_000, 1_000_000})
    void create_validPrice_accepted_BR07(long price) {
        assertThat(Course.create(INSTRUCTOR, category(), "A", "a", null, null, price, NOW).getPrice())
                .isEqualTo(price);
    }

    @ParameterizedTest
    @ValueSource(longs = {-1_000, 1, 999, 1_000, 9_000, 9_999, 10_500, 499_999})
    void create_invalidPrice_rejected_BR07(long price) {
        assertValidationFailed(() -> Course.create(INSTRUCTOR, category(), "A", "a", null, null, price, NOW), "price");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "\t"})
    void create_blankTitle_rejected(String title) {
        assertValidationFailed(() -> Course.create(INSTRUCTOR, category(), title, "a", null, null, 0, NOW), "title");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"Lap-trinh", "lap trinh", "lap--trinh", "-lap", "lap-", "lập-trình", "lap\n"})
    void create_invalidSlug_rejected(String slug) {
        assertValidationFailed(() -> Course.create(INSTRUCTOR, category(), "A", slug, null, null, 0, NOW), "slug");
    }

    @Test
    void create_nullInstructorCategoryOrNow_rejected() {
        assertThatThrownBy(() -> Course.create(null, category(), "A", "a", null, null, 0, NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Course.create(INSTRUCTOR, null, "A", "a", null, null, 0, NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Course.create(INSTRUCTOR, category(), "A", "a", null, null, 0, null))
                .isInstanceOf(NullPointerException.class);
    }

    // ------------------------------------------------------------------------------------------------- edit (BR-33)

    @ParameterizedTest
    @EnumSource(value = CourseStatus.class, names = "PENDING_REVIEW", mode = EnumSource.Mode.EXCLUDE)
    void updateInfo_editableStatus_updatedAndTitleNormalized_D14(CourseStatus status) {
        Course course = courseIn(status);
        Category other = Category.create("Thiết kế", "thiet-ke", NOW);

        course.updateInfo(other, " Thiết kế Đồ Họa ", " Mô tả mới ", "https://cdn.edumy.vn/n.png");

        assertThat(course.getCategory()).isSameAs(other);
        assertThat(course.getTitle()).isEqualTo("Thiết kế Đồ Họa");
        assertThat(course.getTitleNormalized()).isEqualTo("thiet ke do hoa");
        assertThat(course.getDescription()).isEqualTo("Mô tả mới");
        assertThat(course.getThumbnailUrl()).isEqualTo("https://cdn.edumy.vn/n.png");
        assertThat(course.getSlug()).isEqualTo("lap-trinh-java-co-ban");
        assertThat(course.getStatus()).isEqualTo(status);
    }

    @Test
    void updateInfo_pendingReview_rejectedAndUnchanged_BR33() {
        Course course = courseIn(CourseStatus.PENDING_REVIEW);

        assertNotEditable(() -> course.updateInfo(category(), "Mới", null, null));
        assertThat(course.getTitle()).isEqualTo("Lập trình Java cơ bản");
        assertThat(course.isEditable()).isFalse();
        assertNotEditable(course::assertEditable);
    }

    @Test
    void updateInfo_blankTitle_rejectedAndUnchanged() {
        Course course = newCourse();

        assertValidationFailed(() -> course.updateInfo(category(), " ", null, null), "title");
        assertThat(course.getTitle()).isEqualTo("Lập trình Java cơ bản");
        assertThat(course.getTitleNormalized()).isEqualTo("lap trinh java co ban");
        assertThat(course.getDescription()).isEqualTo("Mô tả");
    }

    @ParameterizedTest
    @EnumSource(value = CourseStatus.class, names = "PENDING_REVIEW", mode = EnumSource.Mode.EXCLUDE)
    void changePrice_editableStatus_changed_D14(CourseStatus status) {
        Course course = courseIn(status);

        course.changePrice(0);

        assertThat(course.getPrice()).isZero();
    }

    @Test
    void changePrice_pendingReview_rejected_BR33() {
        Course course = courseIn(CourseStatus.PENDING_REVIEW);

        assertNotEditable(() -> course.changePrice(0));
        assertThat(course.getPrice()).isEqualTo(499_000);
    }

    @Test
    void changePrice_invalidPrice_rejectedAndUnchanged_BR07() {
        Course course = newCourse();

        assertValidationFailed(() -> course.changePrice(5_000), "price");
        assertThat(course.getPrice()).isEqualTo(499_000);
    }

    // ------------------------------------------------------------------------------------------------------- submit

    @ParameterizedTest
    @EnumSource(value = CourseStatus.class, names = {"DRAFT", "REJECTED"})
    void submitForReview_draftOrRejected_pendingWithSubmittedAt_UCI17(CourseStatus status) {
        Course course = courseIn(status);
        Instant at = NOW.plusSeconds(60);

        course.submitForReview(at);

        assertThat(course.getStatus()).isEqualTo(CourseStatus.PENDING_REVIEW);
        assertThat(course.getSubmittedAt()).isEqualTo(at);
    }

    @ParameterizedTest
    @EnumSource(value = CourseStatus.class, names = {"PENDING_REVIEW", "PUBLISHED", "HIDDEN"})
    void submitForReview_otherStatus_rejected(CourseStatus status) {
        Course course = courseIn(status);

        assertConflict(() -> course.submitForReview(NOW.plusSeconds(60)));
        assertThat(course.getStatus()).isEqualTo(status);
    }

    // ------------------------------------------------------------------------------------------------------ approve

    @Test
    void approve_pending_publishedWithReviewerAndPublishedAt_UCA10() {
        Course course = courseIn(CourseStatus.PENDING_REVIEW);
        Instant at = NOW.plusSeconds(3600);

        course.approve(ADMIN, at);

        assertThat(course.getStatus()).isEqualTo(CourseStatus.PUBLISHED);
        assertThat(course.getReviewedBy()).isEqualTo(ADMIN);
        assertThat(course.getReviewedAt()).isEqualTo(at);
        assertThat(course.getPublishedAt()).isEqualTo(at);
    }

    @ParameterizedTest
    @EnumSource(value = CourseStatus.class, names = "PENDING_REVIEW", mode = EnumSource.Mode.EXCLUDE)
    void approve_notPending_rejected(CourseStatus status) {
        Course course = courseIn(status);

        assertConflict(() -> course.approve(ADMIN, NOW.plusSeconds(60)));
        assertThat(course.getStatus()).isEqualTo(status);
    }

    // ------------------------------------------------------------------------------------------------------- reject

    @Test
    void reject_pendingWithNote_rejectedWithNoteAndReviewer_UCA11() {
        Course course = courseIn(CourseStatus.PENDING_REVIEW);
        Instant at = NOW.plusSeconds(60);

        course.reject(ADMIN, "  Thiếu nội dung  ", at);

        assertThat(course.getStatus()).isEqualTo(CourseStatus.REJECTED);
        assertThat(course.getReviewNote()).isEqualTo("Thiếu nội dung");
        assertThat(course.getReviewedBy()).isEqualTo(ADMIN);
        assertThat(course.getReviewedAt()).isEqualTo(at);
        assertThat(course.getPublishedAt()).isNull();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void reject_blankNote_rejectedAndStillPending_BR08(String note) {
        Course course = courseIn(CourseStatus.PENDING_REVIEW);

        assertValidationFailed(() -> course.reject(ADMIN, note, NOW), "reviewNote");
        assertThat(course.getStatus()).isEqualTo(CourseStatus.PENDING_REVIEW);
    }

    @ParameterizedTest
    @EnumSource(value = CourseStatus.class, names = "PENDING_REVIEW", mode = EnumSource.Mode.EXCLUDE)
    void reject_notPending_rejected(CourseStatus status) {
        Course course = courseIn(status);

        assertConflict(() -> course.reject(ADMIN, "Lý do", NOW));
        assertThat(course.getStatus()).isEqualTo(status);
    }

    // -------------------------------------------------------------------------------------------------- hide/unhide

    @Test
    void hide_publishedWithNote_hidden_UCA12() {
        Course course = courseIn(CourseStatus.PUBLISHED);
        Instant publishedAt = course.getPublishedAt();
        Instant at = NOW.plusSeconds(60);

        course.hide(ADMIN, "Vi phạm bản quyền", at);

        assertThat(course.getStatus()).isEqualTo(CourseStatus.HIDDEN);
        assertThat(course.getReviewNote()).isEqualTo("Vi phạm bản quyền");
        assertThat(course.getReviewedAt()).isEqualTo(at);
        assertThat(course.getPublishedAt()).isEqualTo(publishedAt);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  "})
    void hide_blankNote_rejected_BR08(String note) {
        Course course = courseIn(CourseStatus.PUBLISHED);

        assertValidationFailed(() -> course.hide(ADMIN, note, NOW), "reviewNote");
        assertThat(course.getStatus()).isEqualTo(CourseStatus.PUBLISHED);
    }

    @ParameterizedTest
    @EnumSource(value = CourseStatus.class, names = "PUBLISHED", mode = EnumSource.Mode.EXCLUDE)
    void hide_notPublished_rejected(CourseStatus status) {
        Course course = courseIn(status);

        assertConflict(() -> course.hide(ADMIN, "Lý do", NOW));
        assertThat(course.getStatus()).isEqualTo(status);
    }

    @Test
    void unhide_hidden_published_UCA16() {
        Course course = courseIn(CourseStatus.HIDDEN);
        Instant at = NOW.plusSeconds(120);

        course.unhide(ADMIN, at);

        assertThat(course.getStatus()).isEqualTo(CourseStatus.PUBLISHED);
        assertThat(course.getReviewedAt()).isEqualTo(at);
        assertThat(course.getPublishedAt()).isEqualTo(NOW);
    }

    @ParameterizedTest
    @EnumSource(value = CourseStatus.class, names = "HIDDEN", mode = EnumSource.Mode.EXCLUDE)
    void unhide_notHidden_rejected(CourseStatus status) {
        Course course = courseIn(status);

        assertConflict(() -> course.unhide(ADMIN, NOW));
        assertThat(course.getStatus()).isEqualTo(status);
    }

    // ------------------------------------------------------------------------------------------------------- delete

    @ParameterizedTest
    @EnumSource(value = CourseStatus.class, names = {"DRAFT", "REJECTED"})
    void isDeletable_neverPublishedDraftOrRejected_true_BR19(CourseStatus status) {
        assertThat(courseIn(status).isDeletable()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = CourseStatus.class, names = {"PENDING_REVIEW", "PUBLISHED", "HIDDEN"})
    void isDeletable_otherStatus_false_BR19(CourseStatus status) {
        assertThat(courseIn(status).isDeletable()).isFalse();
    }

    @Test
    void equals_transientInstances_notEqualButSelfEqual() {
        Course a = newCourse();
        Course b = newCourse();

        assertThat(a).isEqualTo(a).isNotEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    // ------------------------------------------------------------------------------------------------------ helpers

    static void assertConflict(ThrowingCallable call) {
        assertCode(call, ErrorCode.DATA_CONFLICT);
    }

    static void assertNotEditable(ThrowingCallable call) {
        assertCode(call, ErrorCode.COURSE_NOT_EDITABLE);
    }

    static void assertCode(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getCode()).isEqualTo(code));
    }

    static void assertValidationFailed(ThrowingCallable call, String field) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class, e -> {
            assertThat(e.getCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(e.getErrors()).singleElement().satisfies(v -> assertThat(v.field()).isEqualTo(field));
        });
    }
}
