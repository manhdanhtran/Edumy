package com.edumy.authoring.dto.request;

import com.edumy.shared.validation.CoursePrice;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * UC-I03 {@code PATCH /instructor/courses/{id}}: only the fields sent change; an absent (or {@code null}) field is left
 * as it is. {@code ""} clears the description / thumbnail; a blank title → 400 {@code VALIDATION_FAILED}. Same limits
 * as {@link CreateCourseRequest}. The slug is never changed (SRS UC-I03 lists name, description, image, category,
 * price only). A new {@code categoryId} must be active (422 {@code COURSE_CATEGORY_UNAVAILABLE}); keeping the current
 * one is always allowed.
 */
public record UpdateCourseRequest(
        @Size(max = 200) String title,
        @Size(max = 16_383) String description,
        @Size(max = 500) @Pattern(regexp = "\\s*|https?://\\S+", message = "{edumy.course.thumbnail-url.invalid}")
        String thumbnailUrl,
        @Positive Long categoryId,
        @CoursePrice Long price) {
}
