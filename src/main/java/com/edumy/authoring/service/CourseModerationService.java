package com.edumy.authoring.service;

import java.time.Clock;
import java.util.Objects;

import com.edumy.authoring.api.CourseApproved;
import com.edumy.authoring.api.CourseHidden;
import com.edumy.authoring.api.CourseRejected;
import com.edumy.authoring.api.CourseUnhidden;
import com.edumy.authoring.entity.Course;
import com.edumy.authoring.repository.CourseRepository;
import com.edumy.identity.api.RoleQueryApi;
import com.edumy.shared.error.BusinessException;
import com.edumy.shared.error.ErrorCode;
import com.edumy.shared.security.RoleCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Course moderation by the admin: UC-A10 approve, UC-A11 reject, UC-A12 hide, UC-A16 unhide (state machine docs/ai/02
 * §2.2, enforced by {@link Course}). The controller already required the {@code admin} JWT role; here the admin role
 * is re-checked in the database (BR-26, UC-A10 E2 "không còn quyền Admin" → 403 {@code ACCESS_DENIED}), like
 * {@code identity}'s admin writes. The course row is locked ({@code SELECT … FOR UPDATE}): two admins deciding at the
 * same time are serialized and the second gets 409 {@code COURSE_INVALID_TRANSITION} (UC-A10 E1 "không ghi đè").
 * Events are published inside the transaction; mails and cache eviction run after commit.
 */
@Service
public class CourseModerationService {

    private static final Logger log = LoggerFactory.getLogger(CourseModerationService.class);

    private final CourseRepository courses;
    private final RoleQueryApi roles;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public CourseModerationService(CourseRepository courses, RoleQueryApi roles, ApplicationEventPublisher events,
                                   Clock clock) {
        this.courses = courses;
        this.roles = roles;
        this.events = events;
        this.clock = clock;
    }

    /** UC-A10: pending_review → published; {@code published_at} only when not set yet (first publication kept). */
    @Transactional
    public void approve(Long adminId, Long courseId) {
        Course course = lockAsAdmin(adminId, courseId);
        course.approve(adminId, clock.instant());
        courses.flush();
        events.publishEvent(new CourseApproved(course.getId(), course.getSlug()));
        log.info("Course approved: courseId={}, by adminId={}", course.getId(), adminId);
    }

    /** UC-A11: pending_review → rejected with the reason (BR-08, validated non-blank by the request DTO). */
    @Transactional
    public void reject(Long adminId, Long courseId, String reason) {
        Course course = lockAsAdmin(adminId, courseId);
        course.reject(adminId, reason, clock.instant());
        courses.flush();
        events.publishEvent(new CourseRejected(course.getId(), course.getSlug(), course.getReviewNote()));
        log.info("Course rejected: courseId={}, by adminId={}", course.getId(), adminId);
    }

    /** UC-A12: published → hidden with the reason (BR-08); enrolled students keep access (BR-31). */
    @Transactional
    public void hide(Long adminId, Long courseId, String reason) {
        Course course = lockAsAdmin(adminId, courseId);
        course.hide(adminId, reason, clock.instant());
        courses.flush();
        events.publishEvent(new CourseHidden(course.getId(), course.getSlug(), course.getReviewNote()));
        log.info("Course hidden: courseId={}, by adminId={}", course.getId(), adminId);
    }

    /** UC-A16: hidden → published; the last hiding reason stays as history ({@code review_note}, DDD). */
    @Transactional
    public void unhide(Long adminId, Long courseId) {
        Course course = lockAsAdmin(adminId, courseId);
        course.unhide(adminId, clock.instant());
        courses.flush();
        events.publishEvent(new CourseUnhidden(course.getId(), course.getSlug()));
        log.info("Course unhidden: courseId={}, by adminId={}", course.getId(), adminId);
    }

    private Course lockAsAdmin(Long adminId, Long courseId) {
        Objects.requireNonNull(adminId, "adminId");
        if (!roles.hasActiveRole(adminId, RoleCode.ADMIN)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED); // BR-26: admin role re-checked at action time
        }
        return courses.findByIdForUpdate(courseId).orElseThrow(BusinessException::notFound);
    }
}
