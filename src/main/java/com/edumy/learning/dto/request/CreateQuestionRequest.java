package com.edumy.learning.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * UC-S17 {@code POST /courses/{courseId}/questions}: title ≤ 200 and content ≤ 5 000 characters of plain text, both
 * required and non-blank (D-44, E2); optional {@code lessonId} of the same course (BR-37, checked by the service).
 */
public record CreateQuestionRequest(
        @NotBlank @Size(max = 200) String title,
        @NotBlank @Size(max = 5000) String content,
        @Positive Long lessonId) {
}
