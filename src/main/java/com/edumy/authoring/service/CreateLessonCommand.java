package com.edumy.authoring.service;

/**
 * UC-I09 input of {@link LessonService#create}: already validated (Bean Validation on the HTTP request);
 * {@code contentType} is a {@code lessons.content_type} code ({@code video} / {@code article}).
 */
public record CreateLessonCommand(String title, String contentType, String videoUrl, String articleContent,
                                  Integer durationSeconds, boolean preview) {
}
