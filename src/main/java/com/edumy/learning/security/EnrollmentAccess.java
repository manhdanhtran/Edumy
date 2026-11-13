package com.edumy.learning.security;

import com.edumy.authoring.api.CourseAccessApi;
import com.edumy.authoring.api.CourseQueryApi;
import com.edumy.authoring.api.LessonQueryApi;
import com.edumy.identity.api.RoleQueryApi;
import com.edumy.learning.repository.EnrollmentRepository;
import com.edumy.shared.error.BusinessException;
import com.edumy.shared.security.AuthenticatedUser;
import com.edumy.shared.security.RoleCode;
import org.springframework.stereotype.Component;

/**
 * {@code @enrollmentAccess} for {@code @PreAuthorize} on learning endpoints (docs/ai/04 §4.3, UC-S09…S20). Every
 * method answers {@code true} or throws {@code RESOURCE_NOT_FOUND} (404, NFR-01): an unknown course and a course the
 * caller has no relation to look the same. No role is required up front: the enrollment, the course-instructor
 * relation or the admin role is the permission, and the instructor / admin roles are read from the database (BR-26).
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
            throw BusinessException.notFound();
        }
        return true;
    }

    /** D-32 / UC-S09: enrolled, or course instructor (BR-26), or admin (active role in the database). */
    public boolean canViewContent(Long courseId) {
        if (!canView(callerId(), courseId)) {
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

    private Long courseIdOfLesson(Long lessonId) {
        return lessons.findCourseId(lessonId).orElseThrow(BusinessException::notFound);
    }

    static Long callerId() {
        return AuthenticatedUser.require().id();
    }
}
