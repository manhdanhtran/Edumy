package com.edumy.authoring.security;

import com.edumy.authoring.entity.CourseStatus;
import com.edumy.authoring.repository.CourseRepository;
import com.edumy.identity.api.RoleQueryApi;
import com.edumy.shared.error.BusinessException;
import com.edumy.shared.error.ErrorCode;
import com.edumy.shared.security.AuthenticatedUser;
import com.edumy.shared.security.RoleCode;
import org.springframework.stereotype.Component;

/**
 * {@code @courseAccess} for {@code @PreAuthorize} on instructor course endpoints (docs/ai/04 §4.3, UC-I01…I20). The
 * caller comes from the access JWT; its roles are re-read from the database (BR-26, D-07). Outcome (Q7):
 * <ul>
 *   <li>no <b>active</b> instructor role in the database (even if the JWT still says instructor) → {@code false} →
 *       403 {@code ACCESS_DENIED};</li>
 *   <li>course missing, or the caller has the role but no relation to this course → {@code RESOURCE_NOT_FOUND}
 *       (404, the same body in both cases, NFR-01);</li>
 *   <li>{@link #canEditContent}: a course instructor on a {@code pending_review} course → {@code COURSE_NOT_EDITABLE}
 *       (409, BR-33: the state forbids it, not the permission);</li>
 *   <li>otherwise {@code true}.</li>
 * </ul>
 * A {@link BusinessException} thrown here leaves the SpEL evaluation unwrapped and is rendered by
 * {@code GlobalExceptionHandler}.
 */
@Component("courseAccess")
public class CourseAccess {

    private final CourseRepository courses;
    private final RoleQueryApi roles;

    public CourseAccess(CourseRepository courses, RoleQueryApi roles) {
        this.courses = courses;
        this.roles = roles;
    }

    /** Main instructor of the course (BR-04/06: info, price, invitations, submit). */
    public boolean isMain(Long courseId) {
        Long userId = callerId();
        if (!hasInstructorRole(userId)) {
            return false;
        }
        if (courseId == null || !courses.existsByIdAndMainInstructorId(courseId, userId)) {
            throw BusinessException.notFound();
        }
        return true;
    }

    /** Main instructor or accepted co-instructor of the course (reads: outline, students, questions). */
    public boolean isInstructor(Long courseId) {
        Long userId = callerId();
        if (!hasInstructorRole(userId)) {
            return false;
        }
        requireCourseInstructor(courseId, userId);
        return true;
    }

    /** Course instructor and the course is not {@code pending_review} (BR-33): chapters / lessons writes. */
    public boolean canEditContent(Long courseId) {
        Long userId = callerId();
        if (!hasInstructorRole(userId)) {
            return false;
        }
        requireCourseInstructor(courseId, userId);
        CourseStatus status = courses.findStatusById(courseId).orElseThrow(BusinessException::notFound);
        if (status == CourseStatus.PENDING_REVIEW) {
            throw new BusinessException(ErrorCode.COURSE_NOT_EDITABLE); // BR-33: pending_review is read-only
        }
        return true;
    }

    /** The caller holds an active instructor role in the database (BR-26). For the chapter / lesson beans. */
    boolean callerHasInstructorRole() {
        return hasInstructorRole(callerId());
    }

    private void requireCourseInstructor(Long courseId, Long userId) {
        if (courseId == null || !courses.isCourseInstructor(courseId, userId)) {
            throw BusinessException.notFound();
        }
    }

    private boolean hasInstructorRole(Long userId) {
        return roles.hasActiveRole(userId, RoleCode.INSTRUCTOR);
    }

    private static Long callerId() {
        return AuthenticatedUser.require().id();
    }
}
