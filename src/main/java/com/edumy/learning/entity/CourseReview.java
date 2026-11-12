package com.edumy.learning.entity;

import java.time.Instant;
import java.util.Objects;

import com.edumy.shared.error.BusinessException;
import com.edumy.shared.error.ErrorCode;
import com.edumy.shared.jpa.JpaEntities;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * {@code course_reviews} (EDU-DDD-001 §5.17): at most one review per enrollment (BR-18, uq_course_reviews_enrollment);
 * student and course come from the enrollment. Rating 1–5 (BR-25, ck_course_reviews_rating), comment ≤ 2000
 * characters (D-35), blank → null.
 * <p>State machine (docs/ai/02 §2.2): new → visible · visible → withdrawn (student) · withdrawn → visible (student
 * re-rates) · visible|withdrawn → hidden (admin) · hidden → visible (admin only, BR-35). The student can neither edit
 * nor withdraw a hidden review → {@code REVIEW_HIDDEN_BY_ADMIN} (BR-35/BR-41); other forbidden transitions →
 * {@code DATA_CONFLICT}. {@code moderated_by} / {@code moderated_at} are set together by the admin transitions and kept
 * as history (ck_course_reviews_moderation_pair, ck_course_reviews_hidden_moderated). Who may act (the enrollment's
 * student, admin role BR-26) is a service concern.
 */
@Entity
@Table(name = "course_reviews")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CourseReview {

    static final int MIN_RATING = 1;
    static final int MAX_RATING = 5;
    static final int COMMENT_MAX_LENGTH = 2000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "enrollment_id", nullable = false, updatable = false)
    private Enrollment enrollment;

    /** SMALLINT 1–5. */
    @Column(name = "rating", nullable = false)
    private Short rating;

    @Column(name = "comment", length = COMMENT_MAX_LENGTH)
    private String comment;

    @Column(name = "visibility_status", nullable = false, length = 20)
    private ReviewStatus visibilityStatus;

    /** Admin of the last hide / restore (BR-26 checked by the service). */
    @Column(name = "moderated_by")
    private Long moderatedBy;

    @Column(name = "moderated_at")
    private Instant moderatedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Maintained by MySQL (ON UPDATE CURRENT_TIMESTAMP(6)); null on a freshly persisted instance until reloaded. */
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    /** UC-S12: new visible review of {@code enrollment}. */
    public static CourseReview create(Enrollment enrollment, int rating, String comment, Instant now) {
        CourseReview review = new CourseReview();
        review.enrollment = Objects.requireNonNull(enrollment, "enrollment");
        review.createdAt = Objects.requireNonNull(now, "now");
        review.rating = requireRating(rating);
        review.comment = normalizeComment(comment);
        review.visibilityStatus = ReviewStatus.VISIBLE;
        return review;
    }

    /**
     * UC-S13: the student changes rating and comment. visible → visible, withdrawn → visible (re-rate); hidden →
     * {@code REVIEW_HIDDEN_BY_ADMIN} (BR-35/BR-41).
     */
    public void update(int newRating, String newComment) {
        requireNotHidden();
        Short validRating = requireRating(newRating);
        String validComment = normalizeComment(newComment);
        rating = validRating;
        comment = validComment;
        visibilityStatus = ReviewStatus.VISIBLE;
    }

    /** UC-S14: visible → withdrawn (student); hidden → {@code REVIEW_HIDDEN_BY_ADMIN}; withdrawn → conflict. */
    public void withdraw() {
        requireNotHidden();
        if (visibilityStatus != ReviewStatus.VISIBLE) {
            throw new BusinessException(ErrorCode.DATA_CONFLICT);
        }
        visibilityStatus = ReviewStatus.WITHDRAWN;
    }

    /** UC-A15: visible|withdrawn → hidden (admin). */
    public void hide(Long adminId, Instant now) {
        Objects.requireNonNull(adminId, "adminId");
        Objects.requireNonNull(now, "now");
        if (visibilityStatus == ReviewStatus.HIDDEN) {
            throw new BusinessException(ErrorCode.DATA_CONFLICT);
        }
        visibilityStatus = ReviewStatus.HIDDEN;
        moderatedBy = adminId;
        moderatedAt = now;
    }

    /** UC-A17: hidden → visible (admin only, BR-35). */
    public void restore(Long adminId, Instant now) {
        Objects.requireNonNull(adminId, "adminId");
        Objects.requireNonNull(now, "now");
        if (visibilityStatus != ReviewStatus.HIDDEN) {
            throw new BusinessException(ErrorCode.DATA_CONFLICT);
        }
        visibilityStatus = ReviewStatus.VISIBLE;
        moderatedBy = adminId;
        moderatedAt = now;
    }

    public boolean isVisible() {
        return visibilityStatus == ReviewStatus.VISIBLE;
    }

    private void requireNotHidden() {
        if (visibilityStatus == ReviewStatus.HIDDEN) {
            throw new BusinessException(ErrorCode.REVIEW_HIDDEN_BY_ADMIN);
        }
    }

    private static Short requireRating(int rating) {
        if (rating < MIN_RATING || rating > MAX_RATING) {
            throw CourseQuestion.invalid("rating", "Số sao phải từ 1 đến 5");
        }
        return (short) rating;
    }

    /** Trimmed comment, null when null/blank, at most 2000 characters (D-35). */
    private static String normalizeComment(String comment) {
        if (comment == null) {
            return null;
        }
        String trimmed = comment.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        return CourseQuestion.requireMaxLength(trimmed, "comment", COMMENT_MAX_LENGTH, "Nhận xét");
    }

    @Override
    public final boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || JpaEntities.effectiveClass(this) != JpaEntities.effectiveClass(o)) {
            return false;
        }
        Long id = getId();
        return id != null && id.equals(((CourseReview) o).getId());
    }

    @Override
    public final int hashCode() {
        return JpaEntities.effectiveClass(this).hashCode();
    }

    @Override
    public String toString() {
        return "CourseReview[id=" + getId() + ", rating=" + rating + ", status=" + visibilityStatus + "]";
    }
}
