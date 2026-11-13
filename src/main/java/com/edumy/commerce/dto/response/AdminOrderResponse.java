package com.edumy.commerce.dto.response;

import java.time.Instant;

/** UC-A13 {@code GET /admin/orders} row: an order with its student ({@code null} name / email if the account is gone). */
public record AdminOrderResponse(
        Long id,
        String orderCode,
        Student student,
        long totalAmount,
        String status,
        String cancelReason,
        long itemCount,
        Instant createdAt,
        Instant paidAt,
        Instant cancelledAt) {

    /** Buyer of the order. */
    public record Student(Long id, String fullName, String email) {
    }
}
