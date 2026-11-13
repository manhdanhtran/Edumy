package com.edumy.authoring.api;

/**
 * A lesson with its content: exactly one of {@code videoUrl} / {@code articleContent} is set, by {@code contentType}
 * (the lower-case code {@code video} / {@code article}).
 */
public record LessonContent(Long id, Long courseId, Long chapterId, String title, String contentType, String videoUrl,
                            String articleContent, Integer durationSeconds) {
}
