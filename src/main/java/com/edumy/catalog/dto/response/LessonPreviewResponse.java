package com.edumy.catalog.dto.response;

/**
 * A preview lesson of a published course (UC-G11, D-11): readable without login; no enrollment or progress is
 * created. {@code contentType} {@code video} ({@code videoUrl}, {@code durationSeconds}) or {@code article}
 * ({@code articleContent}).
 */
public record LessonPreviewResponse(
        Long id,
        String courseSlug,
        String courseTitle,
        String chapterTitle,
        String title,
        String contentType,
        String videoUrl,
        String articleContent,
        Integer durationSeconds) {
}
