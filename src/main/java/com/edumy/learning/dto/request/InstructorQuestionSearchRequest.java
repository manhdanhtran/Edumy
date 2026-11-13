package com.edumy.learning.dto.request;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/**
 * Query parameters of UC-I22 {@code GET /instructor/questions?courseId=&status=unanswered}: {@code courseId} narrows to
 * one course the caller instructs (another course gives 404); {@code status} is absent (every visible question) or
 * {@code unanswered} (no visible instructor answer yet); any other value is a field error (400
 * {@code VALIDATION_FAILED}).
 */
public record InstructorQuestionSearchRequest(
        @Positive Long courseId,
        @Pattern(regexp = InstructorQuestionSearchRequest.UNANSWERED, message = "{edumy.question.status.invalid}")
        String status) {

    public static final String UNANSWERED = "unanswered";

    public InstructorQuestionSearchRequest {
        status = status == null || status.isBlank() ? null : status.strip();
    }

    public boolean unansweredOnly() {
        return UNANSWERED.equals(status);
    }
}
