package com.edumy.catalog.dto.response;

import java.time.Instant;
import java.util.List;

/**
 * Public course page (UC-G04): information, main instructor and accepted co-instructors, outline (chapters → lessons:
 * title, type, duration, preview flag — never the content), average rating (one decimal, {@code null} when there is
 * none) and count of the visible reviews (D-10), and the caller's {@link ViewerContext} (D-12).
 * <p>{@code status} is {@code published}, or {@code hidden} when the caller may still open a hidden course (enrolled,
 * course instructor or admin; UC-G04 precondition, D-08) — the SPA must not offer a hidden course for sale.
 * {@code viewer} is {@code null} for an anonymous caller. Everything except {@code viewer} depends only on the slug
 * (cacheable by slug, docs/ai/05 §5.3).
 */
public record CourseDetailResponse(
        Long id,
        String slug,
        String title,
        String description,
        String thumbnailUrl,
        long price,
        boolean isFree,
        String status,
        Long categoryId,
        String categoryName,
        Instructor mainInstructor,
        List<Instructor> coInstructors,
        Double averageRating,
        long ratingCount,
        int totalLessons,
        long totalDurationSeconds,
        Instant publishedAt,
        Instant updatedAt,
        List<Chapter> chapters,
        ViewerContext viewer) {

    public CourseDetailResponse {
        coInstructors = List.copyOf(coInstructors);
        chapters = List.copyOf(chapters);
    }

    /** The same page with the caller's context (everything else is shared by every caller). */
    public CourseDetailResponse withViewer(ViewerContext viewerContext) {
        return new CourseDetailResponse(id, slug, title, description, thumbnailUrl, price, isFree, status, categoryId,
                categoryName, mainInstructor, coInstructors, averageRating, ratingCount, totalLessons,
                totalDurationSeconds, publishedAt, updatedAt, chapters, viewerContext);
    }

    /** A main or co-instructor shown on the page. */
    public record Instructor(Long id, String fullName) {
    }

    /** A chapter of the outline, lessons in their order. */
    public record Chapter(Long id, String title, List<Lesson> lessons) {

        public Chapter {
            lessons = List.copyOf(lessons);
        }
    }

    /**
     * A lesson of the outline: {@code contentType} {@code video} / {@code article}; {@code durationSeconds} is
     * {@code null} for an article. The content itself is only served by the preview endpoint (UC-G11) or to enrolled
     * students.
     */
    public record Lesson(Long id, String title, String contentType, Integer durationSeconds, boolean isPreview) {
    }

    /**
     * D-12 / UC-G04 step 4 (the SPA picks the action button): {@code owned} = the caller has an enrollment ("Vào
     * học"), {@code inCart} = the course is in the caller's cart ("Xem giỏ hàng"), {@code isInstructor} = main or
     * accepted co-instructor holding an active instructor role ("Quản lý khóa học"; never buys it, BR-32).
     */
    public record ViewerContext(boolean owned, boolean inCart, boolean isInstructor) {
    }
}
