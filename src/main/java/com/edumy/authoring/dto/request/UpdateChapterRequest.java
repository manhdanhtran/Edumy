package com.edumy.authoring.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** UC-I06 {@code PATCH /instructor/chapters/{id}}: the chapter title (its only editable field, SRS "Lưu tên chương"). */
public record UpdateChapterRequest(@NotBlank @Size(max = 200) String title) {
}
