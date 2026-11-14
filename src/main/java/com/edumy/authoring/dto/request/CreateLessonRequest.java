package com.edumy.authoring.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * UC-I09 {@code POST /instructor/chapters/{id}/lessons}: a new lesson appended at the end of the chapter. D-22:
 * {@code contentType} {@code video} carries {@code videoUrl} (http/https, ≤500), {@code article} carries
 * {@code articleContent} (≤16 383 characters, fits the {@code TEXT} column); content of the other type → 400
 * {@code VALIDATION_FAILED} on that field (ck_lessons_content_match is never reached). The content itself may be left
 * empty while authoring; "every lesson has content" is checked at submit (BR-07). {@code durationSeconds} ≥ 0 or
 * absent; {@code isPreview} defaults to {@code false}. Blank content is stored as {@code null}, the title trimmed.
 */
public record CreateLessonRequest(
        @NotBlank @Size(max = 200) String title,
        @NotNull @Pattern(regexp = CONTENT_TYPES, message = "{edumy.lesson.content-type.invalid}")
        String contentType,
        @Size(max = 500) @Pattern(regexp = VIDEO_URL, message = "{edumy.lesson.video-url.invalid}")
        String videoUrl,
        @Size(max = 16_383) String articleContent,
        @PositiveOrZero Integer durationSeconds,
        Boolean isPreview) {

    /** {@code lessons.content_type} codes (ck_lessons_type). */
    public static final String CONTENT_TYPES = "video|article";
    /** Blank (= no video yet) or an http(s) URL. */
    public static final String VIDEO_URL = "\\s*|https?://\\S+";
}
