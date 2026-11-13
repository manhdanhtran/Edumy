package com.edumy.authoring.service;

import com.edumy.authoring.api.CourseQueryApi;
import com.edumy.authoring.api.CourseSummary;
import com.edumy.authoring.entity.CourseStatus;
import com.edumy.authoring.repository.CourseRepository;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
class CourseQueryApiImpl implements CourseQueryApi {

    private final CourseRepository courses;

    CourseQueryApiImpl(CourseRepository courses) {
        this.courses = courses;
    }

    @Override
    public boolean exists(Long courseId) {
        return courseId != null && courses.existsById(courseId);
    }

    @Override
    public Map<Long, CourseSummary> findSummaries(Collection<Long> courseIds) {
        var ids = courseIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return courses.findApiSummariesByIdIn(ids).stream()
                .map(c -> new CourseSummary(c.getId(), c.getTitle(), c.getSlug(), c.getThumbnailUrl(), c.getPrice(),
                        c.getStatus() == CourseStatus.PUBLISHED, c.getMainInstructorId())) // BR-31
                .collect(Collectors.toUnmodifiableMap(CourseSummary::id, Function.identity()));
    }
}
