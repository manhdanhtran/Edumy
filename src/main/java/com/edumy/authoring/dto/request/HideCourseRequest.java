package com.edumy.authoring.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * UC-A12 {@code POST /admin/courses/{id}/hide}: the hiding reason, required (BR-08, UC-A12 E1), stored trimmed in
 * {@code courses.review_note} (≤16 383 characters, TEXT column). Validated here so {@code ck_courses_note_required}
 * is never reached.
 */
public record HideCourseRequest(@NotBlank @Size(max = 16_383) String reason) {
}
