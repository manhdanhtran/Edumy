package com.edumy.learning.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * UC-S12 {@code POST /courses/{courseId}/reviews}: rating 1–5 (required, E3, ck_course_reviews_rating), optional plain
 * text comment of at most 2 000 characters (D-35, VARCHAR(2000)); blank is stored as no comment.
 */
public record CreateReviewRequest(@NotNull @Min(1) @Max(5) Integer rating, @Size(max = 2000) String comment) {
}
