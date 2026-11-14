package com.edumy.authoring.dto.request;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * UC-I10 {@code PATCH /instructor/lessons/{id}}: only the fields sent change (absent or {@code null} = unchanged);
 * {@code ""} clears the video URL / article content. Same limits as {@link CreateLessonRequest}. When
 * {@code contentType} changes, the content of the old type is dropped (D-22, ck_lessons_content_match); content of
 * the other type than the resulting one → 400 {@code VALIDATION_FAILED}. A blank title → 400.
 */
public record UpdateLessonRequest(
        @Size(max = 200) String title,
        @Pattern(regexp = CreateLessonRequest.CONTENT_TYPES, message = "{edumy.lesson.content-type.invalid}")
        String contentType,
        @Size(max = 500)
        @Pattern(regexp = CreateLessonRequest.VIDEO_URL, message = "{edumy.lesson.video-url.invalid}")
        String videoUrl,
        @Size(max = 16_383) String articleContent,
        @PositiveOrZero Integer durationSeconds,
        Boolean isPreview) {
}
