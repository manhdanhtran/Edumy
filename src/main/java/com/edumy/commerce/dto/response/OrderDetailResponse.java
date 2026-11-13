package com.edumy.commerce.dto.response;

import java.time.Instant;
import java.util.List;

/**
 * UC-S07 {@code GET /orders/{orderCode}}: the order with its lines at the <b>price snapshot</b> taken at order time
 * (BR-12, not the current course price) and every payment attempt, newest first (no QR payload here: the current QR
 * comes from {@code GET /orders/{orderCode}/payments/current}). Title / slug / thumbnail are the course's current ones.
 */
public record OrderDetailResponse(
        String orderCode,
        long totalAmount,
        String status,
        String cancelReason,
        Instant createdAt,
        Instant paidAt,
        Instant cancelledAt,
        List<Item> items,
        List<Payment> payments) {

    /** One order line; {@code unitPrice} is the frozen price. */
    public record Item(Long courseId, String title, String slug, String thumbnailUrl, long unitPrice) {
    }

    /** One payment attempt; {@code status} = {@code pending|success|failed|expired|needs_review}. */
    public record Payment(String transactionRef, long amount, String status, Instant qrExpiresAt, Instant confirmedAt,
                          Instant createdAt) {
    }
}
