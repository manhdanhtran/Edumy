package com.edumy.authoring.service;

import com.edumy.authoring.api.CourseAccessApi;
import com.edumy.authoring.repository.CourseRepository;
import com.edumy.identity.api.RoleQueryApi;
import com.edumy.shared.security.RoleCode;
import org.springframework.stereotype.Service;

/** {@link CourseAccessApi}: the (cached) role check first, then one query on the course relation. */
@Service
class CourseAccessApiImpl implements CourseAccessApi {

    private final CourseRepository courses;
    private final RoleQueryApi roles;

    CourseAccessApiImpl(CourseRepository courses, RoleQueryApi roles) {
        this.courses = courses;
        this.roles = roles;
    }

    @Override
    public boolean isMainInstructor(Long userId, Long courseId) {
        return userId != null && courseId != null
                && roles.hasActiveRole(userId, RoleCode.INSTRUCTOR) // BR-26, D-07
                && courses.existsByIdAndMainInstructorId(courseId, userId);
    }

    @Override
    public boolean isCourseInstructor(Long userId, Long courseId) {
        return userId != null && courseId != null
                && roles.hasActiveRole(userId, RoleCode.INSTRUCTOR) // BR-26, D-07
                && courses.isCourseInstructor(courseId, userId);
    }
}
