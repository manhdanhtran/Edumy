package com.edumy.learning.dto.request;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/**
 * Query parameters of UC-I22 {@code GET /instructor/questions?courseId=&status=}: {@code courseId} narrows to one course
 * the caller instructs (another course gives 404); {@code status} = {@code unanswered} (no visible instructor answer
 * yet) — the <b>default</b> when absent or blank (SRS UC-I22 "mặc định lọc 'chưa có phản hồi GV'", P10 Q-b) — or
 * {@code all} (every visible question); any other value is a field error (400 {@code VALIDATION_FAILED}).
 */
public record InstructorQuestionSearchRequest(
        @Positive Long courseId,
        @Pattern(regexp = "unanswered|all", message = "{edumy.question.status.invalid}")
        String status) {

    public static final String UNANSWERED = "unanswered";
    public static final String ALL = "all";

    public InstructorQuestionSearchRequest {
        status = status == null || status.isBlank() ? UNANSWERED : status.strip();
    }

    public boolean unansweredOnly() {
        return UNANSWERED.equals(status);
    }
}
