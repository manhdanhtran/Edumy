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

    /**
     * T2 (ADR-004, NFR-04): purchase enrollment for every course of the command the student does not own yet (UC-S05
     * step 7, BR-17: lifetime, at most one per student and course — an existing one is kept, nothing is added). Runs
     * only inside the caller's transaction ({@code propagation = MANDATORY}): the enrollments commit or roll back
     * together with the order and the payment. Returns the course ids that got a new enrollment (immutable).
     */
    Set<Long> grantForPaidOrder(GrantEnrollmentCommand command);
}
