package com.edumy.learning.dto.response;

/**
 * UC-S09 lesson content: {@code videoUrl} for a {@code video} lesson, {@code articleContent} for an {@code article}
 * (the other is null). {@code completed} is set for an enrolled student only (D-32).
 */
public record LessonContentResponse(
        Long id,
        Long courseId,
        Long chapterId,
        String title,
        String contentType,
        Integer durationSeconds,
        String videoUrl,
        String articleContent,
        Boolean completed) {
}
