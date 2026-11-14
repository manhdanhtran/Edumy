package com.edumy.authoring.service;

/**
 * UC-I10 input of {@link LessonService#update} with PATCH semantics: a {@code null} field is left unchanged;
 * {@code ""} clears the video URL / article content. {@code contentType} is a {@code lessons.content_type} code.
 */
public record UpdateLessonCommand(String title, String contentType, String videoUrl, String articleContent,
                                  Integer durationSeconds, Boolean preview) {
}
