package com.edumy.authoring.dto.response;

import java.time.Instant;

/**
 * {@code GET /instructor/courses/{id}/review-status} (UC-I18): current status (lower-case code), when the course was
 * last submitted, when the admin last decided (approve / reject / hide / unhide), and the latest rejection or hiding
 * reason ({@code null} when there was none). The admin's identity is not part of UC-I18.
 */
public record ReviewStatusResponse(
        Long courseId,
        String status,
        Instant submittedAt,
        Instant reviewedAt,
        String reviewNote) {
}
