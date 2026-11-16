package com.edumy.learning.service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import com.edumy.authoring.api.EnrollmentCountPort;
import com.edumy.learning.repository.EnrollmentRepository;
import com.edumy.learning.repository.EnrollmentRepository.CourseStudentCount;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link EnrollmentCountPort} of authoring, implemented where the enrollments live (P13, dependency inversion). */
@Service
@Transactional(readOnly = true)
class EnrollmentCountPortImpl implements EnrollmentCountPort {

    private final EnrollmentRepository enrollments;

    EnrollmentCountPortImpl(EnrollmentRepository enrollments) {
        this.enrollments = enrollments;
    }

    @Override
    public Map<Long, Long> countStudents(Collection<Long> courseIds) {
        List<Long> ids = courseIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return enrollments.countByCourseIdIn(ids).stream()
                .collect(Collectors.toUnmodifiableMap(CourseStudentCount::getCourseId, CourseStudentCount::getStudents));
    }
}