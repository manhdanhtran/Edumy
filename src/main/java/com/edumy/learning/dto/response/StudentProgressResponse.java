package com.edumy.learning.dto.response;

import java.time.Instant;

/** UC-I19 row: a student of the course with the derived progress; no email, no payment data. */
public record StudentProgressResponse(
        Long enrollmentId,
        Student student,
        String enrollSource,
        Instant enrolledAt,
        int completedLessons,
        int totalLessons,
        int progressPercent,
        Instant lastAccessedAt) {

    /** {@code fullName} is null when the account is gone. */
    public record Student(Long id, String fullName) {
    }
}
