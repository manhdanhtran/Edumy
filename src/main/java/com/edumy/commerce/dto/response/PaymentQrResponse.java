package com.edumy.commerce.dto.response;

import java.time.Instant;

/**
 * UC-S05 {@code POST /orders/{orderCode}/payments}: the pending QR attempt of the order. {@code qrData} is the payload
 * to draw as a QR code, {@code amount} = the order total (BR-14), {@code qrExpiresAt} = creation + 15 min (BR-13,
 * drives the countdown). Same field names as {@code PaymentStatusResponse} (polling) where they overlap.
 */
public record PaymentQrResponse(
        String orderCode,
        String transactionRef,
        String status,
        long amount,
        String qrData,
        Instant qrExpiresAt,
        Instant createdAt) {
}
