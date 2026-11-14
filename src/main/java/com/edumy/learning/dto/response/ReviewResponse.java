package com.edumy.learning.dto.response;

import java.time.Instant;

/**
 * A review of a course (UC-S12…S14, UC-A15, A17). {@code status} = {@code visible | withdrawn | hidden} (docs/ai/02
 * §2.2); {@code updatedAt} is the last change as MySQL stored it.
 */
public record ReviewResponse(
        Long id,
        Long courseId,
        int rating,
        String comment,
        String status,
        Instant createdAt,
        Instant updatedAt) {
}
