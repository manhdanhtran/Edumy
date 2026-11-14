package com.edumy.learning.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * UC-S13 {@code PATCH /reviews/{id}}: "lưu điểm và nhận xét" — both are saved as sent, so {@code rating} is required
 * and an absent / blank {@code comment} clears the comment (same limits as {@link CreateReviewRequest}, D-35).
 */
public record UpdateReviewRequest(@NotNull @Min(1) @Max(5) Integer rating, @Size(max = 2000) String comment) {
}
