package com.edumy.authoring.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * UC-I05 {@code POST /instructor/courses/{id}/chapters}: a new chapter appended at the end of the course. Title ≤200
 * ({@code chapters.title VARCHAR(200)}), stored trimmed.
 */
public record CreateChapterRequest(@NotBlank @Size(max = 200) String title) {
}
