package com.edumy.learning.entity;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import com.edumy.shared.error.BusinessException;
import com.edumy.shared.error.ErrorCode;
import com.edumy.shared.error.FieldViolation;
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
 * {@code course_questions} (EDU-DDD-001 §5.18): a question of the asker's enrollment, optionally about a lesson of the
 * same course (BR-37, V5 triggers). Title ≤ 200 and content ≤ 5000 characters of plain text, trimmed and non-blank
 * (D-44, ck_course_questions_title / _content).
 * <p>State machine (docs/ai/02 §2.2): new → visible · visible → deleted (asker, final; hides the whole thread) ·
 * visible → hidden (admin) · hidden → visible (admin, BR-35). The asker edits or resolves only a visible question
 * (BR-41: hidden by an admin → no edit). Any other transition → {@code DATA_CONFLICT}. {@code moderated_by} /
 * {@code moderated_at} are set together by the admin transitions and kept as history
 * (ck_course_questions_moderation_pair, ck_course_questions_hidden_moderated). Who may act (asker, admin role BR-26)
 * is a service / access-bean concern.
 */
@Entity
@Table(name = "course_questions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CourseQuestion {

    static final int TITLE_MAX_LENGTH = 200;
    static final int CONTENT_MAX_LENGTH = 5000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Asker's enrollment: gives the asker ({@code studentId}) and the course. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "enrollment_id", nullable = false, updatable = false)
    private Enrollment enrollment;

    /**
     * Lesson (authoring): plain id, optional. Never changed by the application; MySQL sets it to NULL when the lesson
     * is deleted (FK SET NULL), so a loaded instance may be stale until reloaded.
     */
    @Column(name = "lesson_id", updatable = false)
    private Long lessonId;

    @Column(name = "title", nullable = false, length = TITLE_MAX_LENGTH)
    private String title;

    @Column(name = "content", nullable = false, length = CONTENT_MAX_LENGTH)
    private String content;

    @Column(name = "is_resolved", nullable = false)
    private boolean resolved;

    @Column(name = "visibility_status", nullable = false, length = 20)
    private ContentStatus visibilityStatus;

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

    /** UC-S17: new visible, unresolved question of {@code enrollment}, about {@code lessonId} or the whole course. */
    public static CourseQuestion ask(Enrollment enrollment, Long lessonId, String title, String content,
                                     Instant now) {
        CourseQuestion question = new CourseQuestion();
        question.enrollment = Objects.requireNonNull(enrollment, "enrollment");
        question.lessonId = lessonId;
        question.title = requireText(title, "title", TITLE_MAX_LENGTH, "Tiêu đề câu hỏi");
        question.content = requireText(content, "content", CONTENT_MAX_LENGTH, "Nội dung câu hỏi");
        question.visibilityStatus = ContentStatus.VISIBLE;
        question.createdAt = Objects.requireNonNull(now, "now");
        return question;
    }

    /** UC-S19: the asker edits title and content of a visible question (deleted / hidden, BR-41 → conflict). */
    public void edit(String newTitle, String newContent) {
        requireVisible();
        String validTitle = requireText(newTitle, "title", TITLE_MAX_LENGTH, "Tiêu đề câu hỏi");
        String validContent = requireText(newContent, "content", CONTENT_MAX_LENGTH, "Nội dung câu hỏi");
        title = validTitle;
        content = validContent;
    }

    /**
     * {@code POST /questions/{id}/resolve}: the asker marks a visible question resolved. Idempotent. The SRS has no
     * "reopen", so there is no way back.
     */
    public void markResolved() {
        requireVisible();
        resolved = true;
    }

    /** UC-S19: visible → deleted (asker; final, the whole thread disappears). */
    public void delete() {
        requireVisible();
        visibilityStatus = ContentStatus.DELETED;
    }

    /** UC-A19: visible → hidden (admin). */
    public void hide(Long adminId, Instant now) {
        Objects.requireNonNull(adminId, "adminId");
        Objects.requireNonNull(now, "now");
        requireVisible();
        visibilityStatus = ContentStatus.HIDDEN;
        moderatedBy = adminId;
        moderatedAt = now;
    }

    /** UC-A19: hidden → visible (admin only, BR-35). */
    public void restore(Long adminId, Instant now) {
        Objects.requireNonNull(adminId, "adminId");
        Objects.requireNonNull(now, "now");
        if (visibilityStatus != ContentStatus.HIDDEN) {
            throw new BusinessException(ErrorCode.DATA_CONFLICT);
        }
        visibilityStatus = ContentStatus.VISIBLE;
        moderatedBy = adminId;
        moderatedAt = now;
    }

    public boolean isVisible() {
        return visibilityStatus == ContentStatus.VISIBLE;
    }

    /** The asker = the student of the question's enrollment (loads the enrollment). */
    public boolean isAskedBy(Long userId) {
        return enrollment.isOwnedBy(userId);
    }

    private void requireVisible() {
        if (visibilityStatus != ContentStatus.VISIBLE) {
            throw new BusinessException(ErrorCode.DATA_CONFLICT);
        }
    }

    /**
     * Trimmed, non-blank plain text of at most {@code maxLength} characters (MySQL counts code points); otherwise
     * {@code VALIDATION_FAILED} on {@code field}. {@code label} is the Vietnamese name of the field.
     */
    static String requireText(String value, String field, int maxLength, String label) {
        String trimmed = value == null ? "" : value.strip();
        if (trimmed.isEmpty()) {
            throw invalid(field, label + " không được để trống");
        }
        return requireMaxLength(trimmed, field, maxLength, label);
    }

    static String requireMaxLength(String value, String field, int maxLength, String label) {
        if (value.codePointCount(0, value.length()) > maxLength) {
            throw invalid(field, label + " tối đa " + maxLength + " ký tự");
        }
        return value;
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
        return id != null && id.equals(((CourseQuestion) o).getId());
    }

    @Override
    public final int hashCode() {
        return JpaEntities.effectiveClass(this).hashCode();
    }

    @Override
    public String toString() {
        return "CourseQuestion[id=" + getId() + ", status=" + visibilityStatus + ", resolved=" + resolved + "]";
    }
}
