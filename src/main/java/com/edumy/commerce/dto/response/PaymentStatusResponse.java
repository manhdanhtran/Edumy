package com.edumy.commerce.dto.response;

import java.time.Instant;

/**
 * UC-S05 polling {@code GET /orders/{orderCode}/payments/current}: the pending attempt of the order, else its latest
 * one. {@code orderStatus} tells the SPA when the order became {@code paid}. {@code qrData} (the QR payload to draw)
 * is only present while {@code status = pending}, otherwise {@code null}; {@code qrExpiresAt} drives the countdown.
 */
public record PaymentStatusResponse(
        String orderCode,
        String orderStatus,
        String transactionRef,
        String status,
        long amount,
        String qrData,
        Instant qrExpiresAt,
        Instant confirmedAt,
        Instant createdAt) {
}
