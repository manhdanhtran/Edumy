package com.edumy.learning.dto.response;

import java.time.Instant;

/**
 * UC-S10 result: the lesson's state and the course progress after the change (D-33: floor(done × 100 / lessons now)).
 * {@code certificateCode} is set once the enrollment has a certificate (it stays after an unmark, BR-40);
 * {@code certificateIssued} is true only on the request that issued it (the congratulation of step 4).
 */
public record ProgressResponse(
        Long lessonId,
        boolean completed,
        Instant completedAt,
        int completedLessons,
        int totalLessons,
        int progressPercent,
        String certificateCode,
        boolean certificateIssued) {
}
