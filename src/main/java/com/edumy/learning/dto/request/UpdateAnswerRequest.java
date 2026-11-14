package com.edumy.learning.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** UC-I21 A1 / UC-S19 {@code PATCH /answers/{id}}: the new content, ≤ 5 000 characters of plain text (D-44). */
public record UpdateAnswerRequest(@NotBlank @Size(max = 5000) String content) {
}
