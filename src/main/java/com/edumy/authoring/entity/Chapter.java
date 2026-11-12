package com.edumy.authoring.entity;

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
 * {@code chapters} (EDU-DDD-001 §5.8). {@code sort_order} is unique per course (uq_chapters_course_order) and changed
 * only by the two-phase reorder of {@code ChapterRepository} (docs/ai/03 §3.4). BR-33 (course pending_review is
 * read-only) is checked by the service through {@link Course#assertEditable()}.
 */
@Entity
@Table(name = "chapters")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Chapter {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "course_id", nullable = false, updatable = false)
    private Course course;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    /** ck_chapters_order: > 0. */
    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Maintained by MySQL (ON UPDATE CURRENT_TIMESTAMP(6)); null on a freshly persisted instance until reloaded. */
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    /** UC-I05: new chapter at position {@code sortOrder} (the service appends: max + 1). Title stored trimmed. */
    public static Chapter create(Course course, String title, int sortOrder, Instant now) {
        Chapter chapter = new Chapter();
        chapter.course = Objects.requireNonNull(course, "course");
        chapter.title = requireTitle(title);
        chapter.sortOrder = requirePositiveOrder(sortOrder);
        chapter.createdAt = Objects.requireNonNull(now, "now");
        return chapter;
    }

    /** UC-I06. */
    public void rename(String newTitle) {
        title = requireTitle(newTitle);
    }

    private static String requireTitle(String title) {
        return Course.requireText(title, "title", "Tiêu đề chương không được để trống");
    }

    /** ck_chapters_order / ck_lessons_order. Positions are computed by services, never typed by users. */
    static int requirePositiveOrder(int sortOrder) {
        if (sortOrder <= 0) {
            throw new IllegalArgumentException("sortOrder must be > 0");
        }
        return sortOrder;
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
        return id != null && id.equals(((Chapter) o).getId());
    }

    @Override
    public final int hashCode() {
        return JpaEntities.effectiveClass(this).hashCode();
    }

    @Override
    public String toString() {
        return "Chapter[id=" + getId() + ", sortOrder=" + sortOrder + "]";
    }
}
