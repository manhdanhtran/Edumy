package com.edumy.learning.service;

import com.edumy.learning.api.EnrollmentApi;
import com.edumy.learning.api.GrantEnrollmentCommand;
import com.edumy.learning.repository.EnrollmentRepository;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** {@link EnrollmentApi}: existence queries on {@code enrollments} and the T2 purchase grant. */
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

    /**
     * One {@code INSERT IGNORE} per course (BR-17, docs/ai/03 §3.2): an existing enrollment of the pair — free, from
     * another order, or a concurrent grant — returns 0 and is kept; a unique violation never marks the caller's
     * transaction rollback-only. The order id is never null, so ck_enrollments_source_order holds.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Set<Long> grantForPaidOrder(GrantEnrollmentCommand command) {
        Set<Long> granted = new LinkedHashSet<>();
        for (Long courseId : command.courseIds().stream().filter(Objects::nonNull).distinct().toList()) {
            if (enrollments.insertPurchaseIfAbsent(command.studentId(), courseId, command.orderId(),
                    command.enrolledAt()) == 1) {
                granted.add(courseId);
            }
        }
        return Set.copyOf(granted);
    }
}
