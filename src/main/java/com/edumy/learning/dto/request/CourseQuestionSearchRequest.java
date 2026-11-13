package com.edumy.learning.dto.request;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Query parameters of UC-S18 {@code GET /courses/{courseId}/questions?lessonId=&mine=&unanswered=&q=} (paging is the
 * separate {@code Pageable}). Every filter is optional: {@code lessonId} (questions of one lesson), {@code mine=true}
 * (the caller's own questions, hidden ones included), {@code unanswered=true} (no visible instructor answer, D-39),
 * {@code q} (keyword in title or content; blank = none).
 */
public record CourseQuestionSearchRequest(
        @Positive Long lessonId,
        Boolean mine,
        Boolean unanswered,
        @Size(max = 100) String q) {

    public CourseQuestionSearchRequest {
        q = q == null || q.isBlank() ? null : q.strip();
    }

    public boolean mineOnly() {
        return Boolean.TRUE.equals(mine);
    }

    public boolean unansweredOnly() {
        return Boolean.TRUE.equals(unanswered);
    }
}
