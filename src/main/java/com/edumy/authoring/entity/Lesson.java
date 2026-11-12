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
 * {@code lessons} (EDU-DDD-001 §5.9). Content matches the type (ck_lessons_content_match, D-22): a video lesson never
 * has {@code article_content}, an article lesson never has {@code video_url}. The content itself may still be missing
 * while the course is a draft; "every lesson has content" is a BR-07 submit-checklist item ({@link #hasContent()}).
 * A lesson never moves to another chapter ({@code chapter_id} is not updatable; the BR-24/37 triggers would not
 * re-check such a move). BR-33 is checked by the service through {@link Course#assertEditable()}.
 */
@Entity
@Table(name = "lessons")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Lesson {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "chapter_id", nullable = false, updatable = false)
    private Chapter chapter;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "content_type", nullable = false, length = 20)
    private LessonType contentType;

    @Column(name = "video_url", length = 500)
    private String videoUrl;

    @Column(name = "article_content", columnDefinition = "TEXT")
    private String articleContent;

    /** ck_lessons_duration: null or ≥ 0. */
    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    /** Free preview for visitors (UC-G11). */
    @Column(name = "is_preview", nullable = false)
    private boolean preview;

    /** ck_lessons_order: > 0; unique per chapter (uq_lessons_chapter_order); two-phase reorder (docs/ai/03 §3.4). */
    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Maintained by MySQL (ON UPDATE CURRENT_TIMESTAMP(6)); null on a freshly persisted instance until reloaded. */
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    /**
     * UC-I09: new lesson at position {@code sortOrder} (the service appends: max + 1). Blank content becomes null;
     * content of the other type is rejected (ck_lessons_content_match).
     */
    public static Lesson create(Chapter chapter, String title, LessonType contentType, String videoUrl,
                                String articleContent, Integer durationSeconds, boolean preview, int sortOrder,
                                Instant now) {
        Lesson lesson = new Lesson();
        lesson.chapter = Objects.requireNonNull(chapter, "chapter");
        lesson.sortOrder = Chapter.requirePositiveOrder(sortOrder);
        lesson.createdAt = Objects.requireNonNull(now, "now");
        lesson.apply(title, contentType, videoUrl, articleContent, durationSeconds, preview);
        return lesson;
    }

    /** UC-I10: replaces every editable field (the type may change; the content must then match the new type). */
    public void update(String newTitle, LessonType newContentType, String newVideoUrl, String newArticleContent,
                       Integer newDurationSeconds, boolean newPreview) {
        apply(newTitle, newContentType, newVideoUrl, newArticleContent, newDurationSeconds, newPreview);
    }

    /** BR-07 checklist item "every lesson has content": the field of its type is filled. */
    public boolean hasContent() {
        return contentType == LessonType.VIDEO ? videoUrl != null : articleContent != null;
    }

    /** Validates everything first, then assigns, so a rejected update leaves the lesson unchanged. */
    private void apply(String newTitle, LessonType newContentType, String newVideoUrl, String newArticleContent,
                       Integer newDurationSeconds, boolean newPreview) {
        String validTitle = Course.requireText(newTitle, "title", "Tiêu đề bài học không được để trống");
        Objects.requireNonNull(newContentType, "contentType");
        String video = Course.blankToNull(newVideoUrl);
        String article = Course.blankToNull(newArticleContent);
        if (newContentType == LessonType.VIDEO && article != null) {
            throw Course.invalid("articleContent", "Bài học video không có nội dung bài viết");
        }
        if (newContentType == LessonType.ARTICLE && video != null) {
            throw Course.invalid("videoUrl", "Bài học dạng bài viết không có đường dẫn video");
        }
        if (newDurationSeconds != null && newDurationSeconds < 0) {
            throw Course.invalid("durationSeconds", "Thời lượng không được âm");
        }
        title = validTitle;
        contentType = newContentType;
        videoUrl = video;
        articleContent = article;
        durationSeconds = newDurationSeconds;
        preview = newPreview;
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
        return id != null && id.equals(((Lesson) o).getId());
    }

    @Override
    public final int hashCode() {
        return JpaEntities.effectiveClass(this).hashCode();
    }

    @Override
    public String toString() {
        return "Lesson[id=" + getId() + ", type=" + contentType + ", sortOrder=" + sortOrder + "]";
    }
}
