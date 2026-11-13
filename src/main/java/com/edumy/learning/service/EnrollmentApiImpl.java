package com.edumy.learning.service;

import com.edumy.learning.api.EnrollmentApi;
import com.edumy.learning.repository.EnrollmentRepository;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link EnrollmentApi}: plain existence queries on {@code enrollments}. */
@Service
@Transactional(readOnly = true)
class EnrollmentApiImpl implements EnrollmentApi {

    private final EnrollmentRepository enrollments;

    EnrollmentApiImpl(EnrollmentRepository enrollments) {
        this.enrollments = enrollments;
    }

    @Override
    public boolean owns(Long studentId, Long courseId) {
        return studentId != null && courseId != null && enrollments.existsByStudentIdAndCourseId(studentId, courseId);
    }

    @Override
    public Set<Long> findOwnedCourseIds(Long studentId, Collection<Long> courseIds) {
        List<Long> ids = courseIds.stream().filter(Objects::nonNull).distinct().toList();
        if (studentId == null || ids.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(enrollments.findCourseIdsByStudentIdAndCourseIdIn(studentId, ids));
    }
}
