package com.edumy.authoring.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import com.edumy.authoring.api.CoInstructorInvited;
import com.edumy.authoring.api.CourseContentChanged;
import com.edumy.authoring.entity.Course;
import com.edumy.authoring.entity.CourseCoInstructor;
import com.edumy.authoring.entity.InvitationStatus;
import com.edumy.authoring.repository.CourseCoInstructorRepository;
import com.edumy.authoring.repository.CourseRepository;
import com.edumy.identity.api.RoleQueryApi;
import com.edumy.identity.api.UserQueryApi;
import com.edumy.shared.error.BusinessException;
import com.edumy.shared.error.ErrorCode;
import com.edumy.shared.security.RoleCode;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Co-instructor invitations (UC-I13 invite, UC-I14 accept, UC-I15 decline, UC-I16 remove / revoke, UC-I20 leave),
 * state machine SRS §3.4.4 through the {@link CourseCoInstructor} methods. Who may call was decided by
 * {@code @courseAccess} before (BR-26 active instructor role in the database; BR-06 main instructor for invite and
 * remove; course instructor for leave; invitee scoping here for accept / decline).
 * <p>No course status is a precondition (UC-I13 names only the main instructor and the role; BR-05 / BR-21: invitations
 * and their answers are independent of authoring and review), so these work in {@code pending_review} too.
 * <p>Every method locks the course row ({@code SELECT … FOR UPDATE}) <b>before</b> reading the invitation row: one lock
 * order for all invitation writes (and the course delete), so the pre-checks are reliable and nothing deadlocks.
 * <p>{@link CourseContentChanged} is published when the accepted co-instructors of a published / hidden course change
 * (the catalog page lists them, P08 evicts it).
 */
@Service
public class CoInstructorService {

    private final CourseRepository courses;
    private final CourseCoInstructorRepository coInstructors;
    private final UserQueryApi users;
    private final RoleQueryApi roles;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public CoInstructorService(CourseRepository courses, CourseCoInstructorRepository coInstructors,
                               UserQueryApi users, RoleQueryApi roles, ApplicationEventPublisher events, Clock clock) {
        this.courses = courses;
        this.coInstructors = coInstructors;
        this.users = users;
        this.roles = roles;
        this.events = events;
        this.clock = clock;
    }

    /**
     * UC-I13. The invitee must have an account with an active instructor role (E1: one code for "no account" and "not
     * an instructor", as the SRS gives one message — 422 {@code COURSE_CO_INSTRUCTOR_NOT_ELIGIBLE}), must not be the
     * main instructor (E3 / BR-02 → 422 {@code COURSE_CO_INSTRUCTOR_IS_MAIN}, also the V5 trigger) and must not have
     * a pending or accepted invitation (E2 → 409 {@code COURSE_CO_INSTRUCTOR_ALREADY_INVITED}). A declined / removed
     * row is reused (D-18: same id, back to pending, inviter and time renewed). Publishes {@link CoInstructorInvited}
     * (invitation mail after commit; E4: a mail failure leaves the invitation valid). Returns the invitee's user id.
     */
    @Transactional
    public Long invite(Long courseId, Long inviterId, String email) {
        Course course = lockCourse(courseId);
        Long inviteeId = users.findIdByEmail(email)
                .filter(id -> roles.hasActiveRole(id, RoleCode.INSTRUCTOR))
                .orElseThrow(() -> new BusinessException(ErrorCode.COURSE_CO_INSTRUCTOR_NOT_ELIGIBLE));
        if (course.isMainInstructor(inviteeId)) {
            throw new BusinessException(ErrorCode.COURSE_CO_INSTRUCTOR_IS_MAIN); // BR-02, UC-I13 E3
        }
        Instant now = clock.instant();
        Optional<CourseCoInstructor> existing = coInstructors.findByCourseIdAndInstructorId(courseId, inviteeId);
        CourseCoInstructor row;
        if (existing.isPresent()) {
            row = existing.get();
            if (row.getInvitationStatus() == InvitationStatus.PENDING || row.isAccepted()) {
                throw new BusinessException(ErrorCode.COURSE_CO_INSTRUCTOR_ALREADY_INVITED); // UC-I13 E2
            }
            row.reinvite(inviterId, now); // D-18: the same row, never a second one
            coInstructors.flush();
        } else {
            row = coInstructors.saveAndFlush(CourseCoInstructor.invite(course, inviteeId, inviterId, now));
        }
        events.publishEvent(new CoInstructorInvited(row.getId(), courseId, inviteeId, inviterId));
        return inviteeId;
    }

    /**
     * UC-I14: the caller's pending invitation → accepted; co-editing rights from the next request (BR-04). Unknown or
     * someone else's invitation → 404 (NFR-01); not pending any more (revoked, already answered) → 409
     * {@code INVITATION_NOT_PENDING}.
     */
    @Transactional
    public void accept(Long invitationId, Long instructorId) {
        CourseCoInstructor row = lockedPendingInvitation(invitationId, instructorId);
        row.accept(clock.instant());
        coInstructors.flush();
        catalogChanged(row.getCourse());
    }

    /** UC-I15: the caller's pending invitation → declined; no rights, review unaffected (BR-21). Same errors as accept. */
    @Transactional
    public void decline(Long invitationId, Long instructorId) {
        CourseCoInstructor row = lockedPendingInvitation(invitationId, instructorId);
        row.decline(clock.instant());
        coInstructors.flush();
    }

    /**
     * UC-I16: the main instructor revokes a pending invitation or removes an accepted co-instructor → removed. Every
     * later action of that user on the course is refused at once ({@code @courseAccess} reads the row on each request;
     * old Q&amp;A answers stay, D-43). No pending / accepted invitation of {@code instructorId} → 404.
     */
    @Transactional
    public void remove(Long courseId, Long instructorId) {
        Course course = lockCourse(courseId);
        CourseCoInstructor row = coInstructors.findByCourseIdAndInstructorId(courseId, instructorId)
                .filter(r -> r.getInvitationStatus() == InvitationStatus.PENDING || r.isAccepted())
                .orElseThrow(BusinessException::notFound);
        boolean wasAccepted = row.isAccepted();
        row.remove();
        coInstructors.flush();
        if (wasAccepted) {
            catalogChanged(course);
        }
    }

    /**
     * UC-I20 (D-19): an accepted co-instructor leaves → removed. The main instructor never leaves (RBAC §3.3, BR-02) →
     * 403 {@code COURSE_MAIN_INSTRUCTOR_LEAVE_FORBIDDEN}. Anyone else without an accepted invitation was refused by
     * {@code @courseAccess.isInstructor} (404); one removed meanwhile gets 404 too.
     */
    @Transactional
    public void leave(Long courseId, Long instructorId) {
        Course course = lockCourse(courseId);
        if (course.isMainInstructor(instructorId)) {
            throw new BusinessException(ErrorCode.COURSE_MAIN_INSTRUCTOR_LEAVE_FORBIDDEN);
        }
        CourseCoInstructor row = coInstructors.findByCourseIdAndInstructorId(courseId, instructorId)
                .filter(CourseCoInstructor::isAccepted)
                .orElseThrow(BusinessException::notFound);
        row.remove();
        coInstructors.flush();
        catalogChanged(course);
    }

    /** Course of the caller's invitation, locked; then the invitation, which must still be pending. */
    private CourseCoInstructor lockedPendingInvitation(Long invitationId, Long instructorId) {
        Long courseId = coInstructors.findCourseIdByIdAndInstructorId(invitationId, instructorId)
                .orElseThrow(BusinessException::notFound);
        lockCourse(courseId);
        CourseCoInstructor row = coInstructors.findById(invitationId).orElseThrow(BusinessException::notFound);
        if (row.getInvitationStatus() != InvitationStatus.PENDING) {
            throw new BusinessException(ErrorCode.INVITATION_NOT_PENDING); // UC-I14 E "lời mời đã bị thu hồi"
        }
        return row;
    }

    private Course lockCourse(Long courseId) {
        return courses.findByIdForUpdate(courseId).orElseThrow(BusinessException::notFound);
    }

    /** The catalog page of a published / hidden course lists its accepted co-instructors. */
    private void catalogChanged(Course course) {
        if (course.isPublishedOrHidden()) {
            events.publishEvent(new CourseContentChanged(course.getId(), course.getSlug()));
        }
    }
}
