package com.edumy.authoring.dto.response;

import java.time.Instant;

/**
 * A pending co-instructor invitation received by the caller (UC-I14/I15 {@code GET /instructor/invitations}): the
 * invitation id (used by accept / decline), the course and its main instructor, and when it was sent.
 */
public record InvitationResponse(
        Long id,
        Long courseId,
        String courseTitle,
        String courseSlug,
        Instructor mainInstructor,
        Instant invitedAt) {

    /** The course's main instructor (the inviter, BR-06). */
    public record Instructor(Long id, String fullName) {
    }
}
