package com.edumy.authoring.service;

import com.edumy.authoring.api.CourseQueryApi;
import com.edumy.authoring.repository.CourseRepository;
import org.springframework.stereotype.Service;

@Service
class CourseQueryApiImpl implements CourseQueryApi {

    private final CourseRepository courses;

    CourseQueryApiImpl(CourseRepository courses) {
        this.courses = courses;
    }

    @Override
    public boolean exists(Long courseId) {
        return courseId != null && courses.existsById(courseId);
    }
}
