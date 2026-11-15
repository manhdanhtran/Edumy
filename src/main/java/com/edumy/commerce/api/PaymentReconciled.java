package com.edumy.commerce.api;

import java.util.List;
import java.util.Objects;

/**
 * An admin resolved a {@code needs_review} attempt (UC-A18 step 5): {@code action} = {@code activated} (payment
 * recognised, order paid, {@code grantedCourseIds} enrolled) or {@code refund_outside} (refunded outside Edumy, no
 * access). Published inside the reconciliation transaction, delivered after commit; listener: notification sends the
 * "Kết quả đối soát" mail to the student (UC-A18 step 6, SRS §7) — a mail failure never touches the decision.
 */
public record PaymentReconciled(Long paymentId, String orderCode, Long studentId, String action,
                                List<Long> grantedCourseIds) {

    public PaymentReconciled {
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(orderCode, "orderCode");
        Objects.requireNonNull(studentId, "studentId");
        Objects.requireNonNull(action, "action");
        grantedCourseIds = List.copyOf(grantedCourseIds);
    }

    public boolean activated() {
        return "activated".equals(action);
    }
}
