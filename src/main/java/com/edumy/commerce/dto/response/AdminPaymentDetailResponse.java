package com.edumy.commerce.dto.response;

import java.util.List;

/**
 * UC-A18 step 1 {@code GET /admin/payments/{id}} (P11 decision Q4): the attempt ({@link AdminPaymentResponse}), the
 * order lines with their locked prices, the courses of the order the student already owns (E2: every one owned → no
 * activation), and {@code receivedAmount} = what the provider reports as paid for this reference now (looked up when
 * the page is opened; {@code null} when the provider reports nothing paid or cannot be reached — no column stores it).
 */
public record AdminPaymentDetailResponse(
        AdminPaymentResponse payment,
        List<Item> items,
        List<Long> ownedCourseIds,
        Long receivedAmount) {

    /** One order line (title read now, may be {@code null} if the course is gone). */
    public record Item(Long courseId, String title, long unitPrice) {
    }
}
