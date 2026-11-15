package com.edumy.learning.api;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * T2 / UC-A18 (a): purchase enrollments of {@code studentId} for the courses of the paid order {@code orderId}
 * (UC-S05 step 7). {@code courseIds} are the order's lines (they exist: order_items FK). {@code enrolledAt} = the
 * payment confirmation time.
 */
public record GrantEnrollmentCommand(Long studentId, Long orderId, List<Long> courseIds, Instant enrolledAt) {

    public GrantEnrollmentCommand {
        Objects.requireNonNull(studentId, "studentId");
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(enrolledAt, "enrolledAt");
        courseIds = List.copyOf(courseIds);
    }
}
