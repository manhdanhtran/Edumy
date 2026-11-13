package com.edumy.commerce.dto.response;

import java.time.Instant;

/**
 * UC-S06 {@code GET /orders} row: order code, date, total (frozen, BR-27), status, cancel reason and line count. A
 * {@code pending} order offers "continue payment" and "cancel" in the SPA. {@code status} / {@code cancelReason} =
 * lower-case codes ({@code pending|paid|cancelled}; {@code user|replaced|expired}, {@code null} unless the order was
 * cancelled, kept after a late payment, BR-30).
 */
public record OrderSummaryResponse(
        String orderCode,
        long totalAmount,
        String status,
        String cancelReason,
        long itemCount,
        Instant createdAt,
        Instant paidAt,
        Instant cancelledAt) {
}
