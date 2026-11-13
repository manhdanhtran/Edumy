package com.edumy.learning.dto.response;

import java.time.Instant;

/**
 * UC-S18 / UC-I22 list row. {@code instructorReplied} (D-39) = a visible answer by an instructor exists;
 * {@code answerCount} counts visible answers. {@code status} is {@code visible} or {@code hidden} (only the asker and
 * an admin ever see a hidden one). {@code courseTitle} is filled on the instructor list only.
 */
public record QuestionSummaryResponse(
        Long id,
        Long courseId,
        String courseTitle,
        Long lessonId,
        String title,
        boolean resolved,
        String status,
        Asker asker,
        long answerCount,
        boolean instructorReplied,
        Instant createdAt) {

    /** {@code fullName} is null when the account is gone. */
    public record Asker(Long id, String fullName) {
    }
}
