package com.edumy.authoring.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * UC-A11 {@code POST /admin/courses/{id}/reject}: the rejection reason, required (BR-08, D-16), stored trimmed in
 * {@code courses.review_note} (TEXT: ≤16 383 characters fit its 65 535 bytes whatever the characters). Validated here
 * so {@code ck_courses_note_required} is never reached.
 */
public record RejectCourseRequest(@NotBlank @Size(max = 16_383) String reason) {
}
