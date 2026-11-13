package com.edumy.authoring.dto.response;

/**
 * A lesson as its instructors edit it (UC-I09/I10), <b>with</b> its content: {@code contentType} {@code video} →
 * {@code videoUrl}, {@code article} → {@code articleContent} (the other one is {@code null}, D-22).
 * {@code durationSeconds} may be {@code null}; {@code sortOrder} = position in the chapter (1..n).
 */
public record LessonResponse(
        Long id,
        String title,
        String contentType,
        String videoUrl,
        String articleContent,
        Integer durationSeconds,
        boolean isPreview,
        int sortOrder) {
}
