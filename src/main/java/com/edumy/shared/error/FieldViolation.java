package com.edumy.shared.error;

/**
 * One entry of the ProblemDetail {@code errors} array: a Bean Validation field error, or a failed business
 * checklist item (e.g. COURSE_SUBMIT_CHECKLIST_FAILED, BR-07).
 */
public record FieldViolation(String field, String message) {
}
