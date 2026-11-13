package com.edumy.learning.api;

import java.util.Collection;
import java.util.Set;

/**
 * Enrollments seen from other modules (docs/ai/01 §1.9). {@code grantForPaidOrder} (T2, ADR-004) is added with the
 * payment writes (P06).
 */
public interface EnrollmentApi {

    /** The student is enrolled in the course (BR-10), whatever the course status (BR-31). */
    boolean owns(Long studentId, Long courseId);

    /**
     * Those of {@code courseIds} the student owns, in <b>one</b> query (cart lines: no N+1). An empty collection costs
     * no query. The set is immutable.
     */
    Set<Long> findOwnedCourseIds(Long studentId, Collection<Long> courseIds);
}
