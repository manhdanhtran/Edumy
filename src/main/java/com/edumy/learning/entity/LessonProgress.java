package com.edumy.learning.entity;

import java.time.Instant;
import java.util.Objects;

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
 * {@code lesson_progresses} (EDU-DDD-001 §5.16): progress of one enrollment on one lesson
 * (uq_lesson_progresses_enrollment_lesson). The lesson belongs to the enrollment's course (BR-24, checked by the V5
 * triggers on INSERT/UPDATE); enrollment and lesson never change. Completion is manual (D-33): {@code is_completed} ⇔
 * {@code completed_at} (ck_lesson_progresses_completed). {@link #complete} and {@link #uncomplete} are idempotent
 * because {@code PUT /learn/lessons/{id}/completion} is: completing twice keeps the first {@code completed_at}.
 * {@code last_accessed_at} drives "Học tiếp" (D-34) and is set by the application only. The percent is derived,
 * never stored (D-33); certificate issuance at 100 % (BR-39) is a service concern.
 */
@Entity
@Table(name = "lesson_progresses")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LessonProgress {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "enrollment_id", nullable = false, updatable = false)
    private Enrollment enrollment;

    /** Lesson (authoring): plain id. Deleting the lesson deletes this row (FK CASCADE). */
    @Column(name = "lesson_id", nullable = false, updatable = false)
    private Long lessonId;

    @Column(name = "is_completed", nullable = false)
    private boolean completed;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "last_accessed_at", nullable = false)
    private Instant lastAccessedAt;

    /** First access of {@code lessonId} by {@code enrollment} (UC-S09 / D-34): not completed, accessed now. */
    public static LessonProgress start(Enrollment enrollment, Long lessonId, Instant now) {
        LessonProgress progress = new LessonProgress();
        progress.enrollment = Objects.requireNonNull(enrollment, "enrollment");
        progress.lessonId = Objects.requireNonNull(lessonId, "lessonId");
        progress.lastAccessedAt = Objects.requireNonNull(now, "now");
        return progress;
    }

    /** D-34 {@code POST /learn/lessons/{id}/visit}: the lesson was opened now. */
    public void visit(Instant now) {
        lastAccessedAt = Objects.requireNonNull(now, "now");
    }

    /** UC-S10 (D-33): mark the lesson done. Idempotent: an already completed lesson keeps its {@code completed_at}. */
    public void complete(Instant now) {
        Objects.requireNonNull(now, "now");
        if (completed) {
            return;
        }
        completed = true;
        completedAt = now;
    }

    /** UC-S10 (D-33): mark the lesson not done again. Idempotent. A certificate already issued stays (BR-40). */
    public void uncomplete() {
        completed = false;
        completedAt = null;
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
        return id != null && id.equals(((LessonProgress) o).getId());
    }

    @Override
    public final int hashCode() {
        return JpaEntities.effectiveClass(this).hashCode();
    }

    @Override
    public String toString() {
        return "LessonProgress[id=" + getId() + ", lessonId=" + lessonId + ", completed=" + completed + "]";
    }
}
