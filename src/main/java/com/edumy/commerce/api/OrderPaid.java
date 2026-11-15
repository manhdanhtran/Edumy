package com.edumy.commerce.api;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * An order became paid through a verified provider result or lookup (UC-S05 step 7, late result A3 / BR-30): published
 * inside T2, delivered after commit. Listener: notification sends the purchase confirmation (UC-S05 step 9, BR-20); a
 * mail failure never touches the order (AC-03). {@code courseIds} = the order's lines; {@code totalAmount} in VND.
 */
public record OrderPaid(Long orderId, String orderCode, Long studentId, long totalAmount, List<Long> courseIds,
                        Instant paidAt) {

    public OrderPaid {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(orderCode, "orderCode");
        Objects.requireNonNull(studentId, "studentId");
        courseIds = List.copyOf(courseIds);
    }
}
