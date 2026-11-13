package com.edumy.authoring.dto.response;

import java.time.Instant;

/**
 * One course of the admin review queue (UC-A09 {@code GET /admin/courses/pending}): {@code pending_review} courses,
 * oldest submission first. The content itself is read through {@code GET /learn/courses/{courseId}} and
 * {@code GET /learn/lessons/{lessonId}} (admin without enrollment, D-32; UC-A10 step 1).
 */
public record PendingCourseResponse(
        Long id,
        String title,
        String slug,
        long price,
        boolean isFree,
        Long categoryId,
        String categoryName,
        Instructor mainInstructor,
        Instant submittedAt) {

    /** The main instructor who submitted the course. */
    public record Instructor(Long id, String fullName) {
    }
}
