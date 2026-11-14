package com.edumy.authoring.api;

import java.util.Objects;

/**
 * UC-I13 step 5: an instructor was invited to co-author a course (first invitation, or a re-invitation that reused the
 * declined / removed row, D-18). Published inside the inviting transaction; notification mails the invitation to
 * {@code instructorId} after commit (SRS §7 "Lời mời cộng tác"). Ids only: the listener reads the names, the email and
 * the course title through {@code identity::api} / {@code authoring::api}.
 *
 * @param invitationId {@code course_co_instructors.id} (the id accepted / declined by UC-I14 / UC-I15)
 * @param courseId     the course
 * @param instructorId the invited user
 * @param invitedBy    the inviting main instructor (BR-06)
 */
public record CoInstructorInvited(Long invitationId, Long courseId, Long instructorId, Long invitedBy) {

    public CoInstructorInvited {
        Objects.requireNonNull(invitationId, "invitationId");
        Objects.requireNonNull(courseId, "courseId");
        Objects.requireNonNull(instructorId, "instructorId");
        Objects.requireNonNull(invitedBy, "invitedBy");
    }
}
