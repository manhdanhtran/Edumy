package com.edumy.authoring.entity;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

import com.edumy.shared.error.BusinessException;
import com.edumy.shared.error.ErrorCode;
import com.edumy.shared.error.FieldViolation;
import com.edumy.shared.jpa.JpaEntities;
import com.edumy.shared.text.VietnameseNormalizer;
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
 * {@code courses} (EDU-DDD-001 §5.6). State machine (docs/ai/02 §2.2, SRS §3.4): draft|rejected → pending_review →
 * published|rejected; published ⇄ hidden. Any other transition is rejected with {@code DATA_CONFLICT}; editing while
 * pending_review with {@code COURSE_NOT_EDITABLE} (BR-33). Who may act (main instructor BR-04/06, admin) and the BR-07
 * content checklist (chapters/lessons, active category) are service concerns.
 */
@Entity
@Table(name = "courses")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Course {

    /** Same as ck_categories_slug / ck_courses_slug ({@code matches()} anchors both ends like the DB's {@code \z}). */
    private static final Pattern SLUG = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");

    /** BR-07 / ck_courses_price: 0 (free) or ≥ 10 000 VND and a multiple of 1 000. */
    static final long MIN_PAID_PRICE = 10_000L;
    static final long PRICE_STEP = 1_000L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * BR-02: exactly one main instructor, never changed. {@code updatable = false} because the BR-02 trigger only guards
     * course_co_instructors, not an UPDATE of this column (ADR-001). Users live in identity: plain id, no association.
     */
    @Column(name = "main_instructor_id", nullable = false, updatable = false)
    private Long mainInstructorId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    /** Admin who made the last review / moderation decision (approve, reject, hide, unhide). */
    @Column(name = "reviewed_by")
    private Long reviewedBy;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    /** {@link VietnameseNormalizer#normalize} of {@link #title}, always set together with it (docs/ai/03 §3.2). */
    @Column(name = "title_normalized", nullable = false, length = 200)
    private String titleNormalized;

    @Column(name = "slug", nullable = false, length = 220)
    private String slug;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "thumbnail_url", length = 500)
    private String thumbnailUrl;

    /** VND. */
    @Column(name = "price", nullable = false)
    private long price;

    @Column(name = "status", nullable = false, length = 20)
    private CourseStatus status;

    /** Reason of the last reject / hide (BR-08, ck_courses_note_required); kept as history afterwards. */
    @Column(name = "review_note", columnDefinition = "TEXT")
    private String reviewNote;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    /** First publication; never reset (BR-19: a course published once is never deleted). */
    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Maintained by MySQL (ON UPDATE CURRENT_TIMESTAMP(6)); null on a freshly persisted instance until reloaded. */
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    /**
     * UC-I01: new draft course of {@code mainInstructorId}. Title is trimmed and normalized, blank description /
     * thumbnail become null, the price follows BR-07.
     */
    public static Course create(Long mainInstructorId, Category category, String title, String slug,
                                String description, String thumbnailUrl, long price, Instant now) {
        Course course = new Course();
        course.mainInstructorId = Objects.requireNonNull(mainInstructorId, "mainInstructorId");
        course.category = Objects.requireNonNull(category, "category");
        course.setTitle(title);
        course.slug = requireSlug(slug);
        course.description = blankToNull(description);
        course.thumbnailUrl = blankToNull(thumbnailUrl);
        course.price = requireValidPrice(price);
        course.status = CourseStatus.DRAFT;
        course.createdAt = Objects.requireNonNull(now, "now");
        return course;
    }

    /**
     * UC-I03: course information (main instructor only, BR-06). Allowed in every status except pending_review
     * (BR-33); a published course is edited without re-review (D-14). The slug is not changed.
     */
    public void updateInfo(Category newCategory, String newTitle, String newDescription, String newThumbnailUrl) {
        assertEditable();
        Objects.requireNonNull(newCategory, "category");
        String validTitle = requireTitle(newTitle);
        category = newCategory;
        setTitle(validTitle);
        description = blankToNull(newDescription);
        thumbnailUrl = blankToNull(newThumbnailUrl);
    }

    /** UC-I03: price (BR-07 rule), not while pending_review (BR-33). */
    public void changePrice(long newPrice) {
        assertEditable();
        price = requireValidPrice(newPrice);
    }

    /**
     * UC-I17: draft|rejected → pending_review. Only the transition is guarded here; the BR-07 checklist is checked by
     * the service before calling this.
     */
    public void submitForReview(Instant now) {
        Objects.requireNonNull(now, "now");
        if (status != CourseStatus.DRAFT && status != CourseStatus.REJECTED) {
            throw new BusinessException(ErrorCode.DATA_CONFLICT);
        }
        status = CourseStatus.PENDING_REVIEW;
        submittedAt = now;
    }

    /** UC-A10: pending_review → published; {@code published_at} is set on the first publication only. */
    public void approve(Long adminId, Instant now) {
        Objects.requireNonNull(adminId, "adminId");
        Objects.requireNonNull(now, "now");
        requireStatus(CourseStatus.PENDING_REVIEW);
        status = CourseStatus.PUBLISHED;
        reviewedBy = adminId;
        reviewedAt = now;
        if (publishedAt == null) {
            publishedAt = now;
        }
    }

    /** UC-A11: pending_review → rejected, reason required (BR-08). */
    public void reject(Long adminId, String note, Instant now) {
        Objects.requireNonNull(adminId, "adminId");
        Objects.requireNonNull(now, "now");
        String validNote = requireText(note, "reviewNote", "Lý do từ chối không được để trống");
        requireStatus(CourseStatus.PENDING_REVIEW);
        status = CourseStatus.REJECTED;
        reviewNote = validNote;
        reviewedBy = adminId;
        reviewedAt = now;
    }

    /** UC-A12: published → hidden, reason required (BR-08); enrolled students keep access (BR-31). */
    public void hide(Long adminId, String note, Instant now) {
        Objects.requireNonNull(adminId, "adminId");
        Objects.requireNonNull(now, "now");
        String validNote = requireText(note, "reviewNote", "Lý do ẩn khóa học không được để trống");
        requireStatus(CourseStatus.PUBLISHED);
        status = CourseStatus.HIDDEN;
        reviewNote = validNote;
        reviewedBy = adminId;
        reviewedAt = now;
    }

    /** UC-A16: hidden → published ({@code published_at} is already set). */
    public void unhide(Long adminId, Instant now) {
        Objects.requireNonNull(adminId, "adminId");
        Objects.requireNonNull(now, "now");
        requireStatus(CourseStatus.HIDDEN);
        status = CourseStatus.PUBLISHED;
        reviewedBy = adminId;
        reviewedAt = now;
    }

    /** BR-19 / UC-I04: never published and in draft or rejected (the V5 trigger re-checks published_at). */
    public boolean isDeletable() {
        return publishedAt == null && (status == CourseStatus.DRAFT || status == CourseStatus.REJECTED);
    }

    /**
     * Published or hidden (= published once, BR-19): content changes take effect at once without re-review and the
     * catalog must drop its cached outline (D-14, {@code CourseContentChanged}); the course keeps at least one lesson.
     */
    public boolean isPublishedOrHidden() {
        return status == CourseStatus.PUBLISHED || status == CourseStatus.HIDDEN;
    }

    public boolean isEditable() {
        return status != CourseStatus.PENDING_REVIEW;
    }

    /** BR-33: a pending_review course is read-only (info, price, chapters, lessons). */
    public void assertEditable() {
        if (!isEditable()) {
            throw new BusinessException(ErrorCode.COURSE_NOT_EDITABLE);
        }
    }

    public boolean isMainInstructor(Long userId) {
        return mainInstructorId.equals(userId);
    }

    /** title and title_normalized always change together (docs/ai/03 §3.2). */
    private void setTitle(String newTitle) {
        title = requireTitle(newTitle);
        titleNormalized = VietnameseNormalizer.normalize(title);
    }

    private static String requireTitle(String title) {
        return requireText(title, "title", "Tiêu đề khóa học không được để trống");
    }

    private void requireStatus(CourseStatus expected) {
        if (status != expected) {
            throw new BusinessException(ErrorCode.DATA_CONFLICT);
        }
    }

    /** BR-07 / ck_courses_price. */
    public static boolean isValidPrice(long price) {
        return price == 0 || (price >= MIN_PAID_PRICE && price % PRICE_STEP == 0);
    }

    static long requireValidPrice(long price) {
        if (!isValidPrice(price)) {
            throw invalid("price", "Giá phải bằng 0 hoặc từ 10.000 đ trở lên và là bội số của 1.000 đ");
        }
        return price;
    }

    static String requireSlug(String slug) {
        if (slug == null || !SLUG.matcher(slug).matches()) {
            throw invalid("slug", "Slug chỉ gồm chữ thường không dấu, số và dấu gạch ngang");
        }
        return slug;
    }

    /** Trimmed, non-blank user text; otherwise {@code VALIDATION_FAILED} on {@code field}. */
    static String requireText(String value, String field, String message) {
        String trimmed = value == null ? "" : value.strip();
        if (trimmed.isEmpty()) {
            throw invalid(field, message);
        }
        return trimmed;
    }

    /** Trimmed text, or null when null/blank. */
    static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    static BusinessException invalid(String field, String message) {
        return BusinessException.withErrors(ErrorCode.VALIDATION_FAILED, List.of(new FieldViolation(field, message)));
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
        return id != null && id.equals(((Course) o).getId());
    }

    @Override
    public final int hashCode() {
        return JpaEntities.effectiveClass(this).hashCode();
    }

    @Override
    public String toString() {
        return "Course[id=" + getId() + ", slug=" + slug + ", status=" + status + "]";
    }
}
