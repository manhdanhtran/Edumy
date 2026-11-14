package com.edumy.authoring.dto.request;

import com.edumy.shared.validation.CoursePrice;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * UC-I01 {@code POST /instructor/courses}: a new draft course of the caller. Limits: title ≤200 (SRS, docs/ai/05 §5.1),
 * description ≤16 383 characters (fits the DDD {@code TEXT} column of 65 535 bytes whatever the characters),
 * thumbnail URL ≤500 ({@code VARCHAR(500)}, http/https), price BR-07 (VND). The category must exist and be active
 * (checked by the service → 422 {@code COURSE_CATEGORY_UNAVAILABLE}). Blank description / thumbnail are stored as
 * {@code null}; the title is stored trimmed.
 */
public record CreateCourseRequest(
        @NotBlank @Size(max = 200) String title,
        @Size(max = 16_383) String description,
        @Size(max = 500) @Pattern(regexp = "\\s*|https?://\\S+", message = "{edumy.course.thumbnail-url.invalid}")
        String thumbnailUrl,
        @NotNull @Positive Long categoryId,
        @NotNull @CoursePrice Long price) {
}
