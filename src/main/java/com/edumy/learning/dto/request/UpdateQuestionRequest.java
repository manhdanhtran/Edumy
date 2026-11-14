package com.edumy.learning.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** UC-S19 {@code PATCH /questions/{id}}: full replacement of title (≤ 200) and content (≤ 5 000), plain text (D-44). */
public record UpdateQuestionRequest(
        @NotBlank @Size(max = 200) String title,
        @NotBlank @Size(max = 5000) String content) {
}
