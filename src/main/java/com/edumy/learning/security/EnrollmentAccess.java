package com.edumy.learning.security;

import java.util.List;

import com.edumy.authoring.api.CourseAccessApi;
import com.edumy.authoring.api.CourseQueryApi;
import com.edumy.authoring.api.CourseSummary;
import com.edumy.authoring.api.LessonQueryApi;
import com.edumy.identity.api.RoleQueryApi;
import com.edumy.learning.repository.EnrollmentRepository;
import com.edumy.shared.error.BusinessException;
import com.edumy.shared.error.ErrorCode;
import com.edumy.shared.security.AuthenticatedUser;
import com.edumy.shared.security.RoleCode;
import org.springframework.stereotype.Component;

/**
 * {@code @enrollmentAccess} for {@code @PreAuthorize} on learning endpoints (docs/ai/04 §4.3, UC-S09…S20). The
 * content methods answer {@code true} or throw — following the 403/404 principle (ADR-008 §2, P10):
 * <ul>
 *   <li>a <b>published</b> course is public, so a caller without the needed relation gets 403
 *       {@code ENROLLMENT_REQUIRED} and the SPA shows the course page (UC-S09 "Không có quyền → trang giới thiệu
 *       khóa");</li>
 *   <li>any other course (draft, pending, rejected, hidden) and an unknown id answer the same 404
 *       {@code RESOURCE_NOT_FOUND} (NFR-01, UC-G04 E1).</li>
 * </ul>
 * No role is required up front: the enrollment, the course-instructor relation or the admin role is the permission,
 * and the instructor / admin roles are read from the database (BR-26).
 * <p>{@link #isActiveInstructor} / {@link #isCourseInstructor} serve learning's instructor endpoints (UC-I19, UC-I22)
 * through {@code authoring::api} only (P10, structure review SC-M1): no active instructor role → {@code false} (403,
 * before any lookup); not an instructor of the course → 404.
 */
@Component("enrollmentAccess")
public class EnrollmentAccess {

    private final EnrollmentRepository enrollments;
    private final CourseAccessApi courseAccess;
    private final CourseQueryApi courseQuery;
    private final LessonQueryApi lessons;
    private final RoleQueryApi roles;

    public EnrollmentAccess(EnrollmentRepository enrollments, CourseAccessApi courseAccess, CourseQueryApi courseQuery,
                            LessonQueryApi lessons, RoleQueryApi roles) {
        this.enrollments = enrollments;
        this.courseAccess = courseAccess;
        this.courseQuery = courseQuery;
        this.lessons = lessons;
        this.roles = roles;
    }

    /** The caller has an enrollment in the course (progress, reviews, asking questions). */
    public boolean isEnrolled(Long courseId) {
        if (!isEnrolled(callerId(), courseId)) {
            throw denied(courseId);
        }
        return true;
    }

    /** D-32 / UC-S09: enrolled, or course instructor (BR-26), or admin (active role in the database). */
    public boolean canViewContent(Long courseId) {
        if (!canView(callerId(), courseId)) {
            throw denied(courseId);
        }
        return true;
    }

    /** UC-I22 (no course id): the caller holds an active instructor role in the database, else {@code false} (403). */
    public boolean isActiveInstructor() {
        return roles.hasActiveRole(callerId(), RoleCode.INSTRUCTOR);
    }

    /**
     * UC-I19: main or accepted co-instructor of the course with an active instructor role. No role → {@code false}
     * (403, decided before any lookup); not an instructor of this course / unknown id → 404.
     */
    public boolean isCourseInstructor(Long courseId) {
        Long userId = callerId();
        if (!roles.hasActiveRole(userId, RoleCode.INSTRUCTOR)) {
            return false;
        }
        if (courseId == null || !courseAccess.isCourseInstructor(userId, courseId)) {
            throw BusinessException.notFound();
        }
        return true;
    }

    /** {@link #canViewContent} for the lesson's course (UC-S09 endpoints are addressed by lesson id). */
    public boolean canViewLesson(Long lessonId) {
        return canViewContent(courseIdOfLesson(lessonId));
    }

    /** {@link #isEnrolled} for the lesson's course (UC-S10 completion: only enrollments have progress, D-32). */
    public boolean isEnrolledForLesson(Long lessonId) {
        return isEnrolled(courseIdOfLesson(lessonId));
    }

    /** Shared with {@link QaAccess}: may {@code userId} see the content (and the Q&amp;A) of {@code courseId}? */
    boolean canView(Long userId, Long courseId) {
        if (courseId == null) {
            return false;
        }
        return isEnrolled(userId, courseId)
                || courseAccess.isCourseInstructor(userId, courseId)
                || (roles.hasActiveRole(userId, RoleCode.ADMIN) && courseQuery.exists(courseId)); // D-32
    }

    private boolean isEnrolled(Long userId, Long courseId) {
        return courseId != null && enrollments.existsByStudentIdAndCourseId(userId, courseId);
    }

    /** 403/404 principle: a published course is public (403 with a business code), anything else looks unknown. */
    private BusinessException denied(Long courseId) {
        CourseSummary course = courseId == null ? null : courseQuery.findSummaries(List.of(courseId)).get(courseId);
        return course != null && course.published() ? new BusinessException(ErrorCode.ENROLLMENT_REQUIRED)
                : BusinessException.notFound();
    }

    private Long courseIdOfLesson(Long lessonId) {
        return lessons.findCourseId(lessonId).orElseThrow(BusinessException::notFound);
    }

    static Long callerId() {
        return AuthenticatedUser.require().id();
    }
}
