package com.edumy.learning.dto.response;

import java.time.Instant;

/**
 * UC-S08 row: an enrollment with its course card and derived progress (D-33: floor(completed * 100 / total)).
 * {@code lastAccessedLessonId} / {@code lastAccessedAt} drive the "continue learning" button (D-34, null before any
 * visit); {@code certificateCode} is set once the certificate exists. The course stays listed when hidden later (BR-31).
 */
public record EnrollmentResponse(
        Long id,
        Course course,
        String enrollSource,
        Instant enrolledAt,
        int completedLessons,
        int totalLessons,
        int progressPercent,
        Long lastAccessedLessonId,
        Instant lastAccessedAt,
        String certificateCode) {

    public record Course(Long id, String title, String slug, String thumbnailUrl, boolean published) {
    }
}
