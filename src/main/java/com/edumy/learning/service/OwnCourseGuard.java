package com.edumy.learning.service;

import com.edumy.authoring.api.CourseAccessApi;
import com.edumy.shared.error.BusinessException;
import com.edumy.shared.error.ErrorCode;
import org.springframework.stereotype.Component;

/**
 * BR-32 / RBAC §3.3 inside learning (P10, business review BC-M3): an instructor of a course — main or accepted
 * co-instructor, <b>whatever the role state</b> — has no student rights on it: no review, no progress, no certificate,
 * no question. A student who enrolled first and was invited as co-instructor later keeps the enrollment row (BR-17),
 * but these actions answer 422 {@code ENROLLMENT_OWN_COURSE} ("đã có quyền với tư cách giảng viên", UC-S15 E2). Course
 * instructors read the content without progress through D-32 instead.
 */
@Component
public class OwnCourseGuard {

    private final CourseAccessApi courseAccess;

    public OwnCourseGuard(CourseAccessApi courseAccess) {
        this.courseAccess = courseAccess;
    }

    /** 422 {@code ENROLLMENT_OWN_COURSE} when {@code userId} instructs {@code courseId}. */
    public void requireNotInstructor(Long userId, Long courseId) {
        if (isInstructor(userId, courseId)) {
            throw new BusinessException(ErrorCode.ENROLLMENT_OWN_COURSE);
        }
    }

    /** Main or accepted co-instructor of the course, with or without an active instructor role. */
    public boolean isInstructor(Long userId, Long courseId) {
        return courseAccess.hasInstructorRelation(userId, courseId);
    }
}
