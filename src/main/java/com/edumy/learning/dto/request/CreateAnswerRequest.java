package com.edumy.learning.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** UC-I21 / UC-S19 {@code POST /questions/{id}/answers}: content ≤ 5 000 characters of plain text, non-blank (D-44). */
public record CreateAnswerRequest(@NotBlank @Size(max = 5000) String content) {
}
