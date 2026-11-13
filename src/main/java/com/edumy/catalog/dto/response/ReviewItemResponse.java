package com.edumy.catalog.dto.response;

import java.time.Instant;

/** One visible review of a course page (UC-G04 step 3), newest first; {@code reviewerName} = the student's name. */
public record ReviewItemResponse(
        Long id,
        int rating,
        String comment,
        String reviewerName,
        Instant createdAt,
        Instant updatedAt) {
}
