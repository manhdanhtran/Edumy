package com.edumy.commerce.dto.response;

import java.time.Instant;

/**
 * UC-A14 {@code GET /admin/payments} row: one payment attempt with its order and student, and the reconciliation
 * outcome ({@code handledBy} / {@code handledAction} = {@code activated|refund_outside} / {@code handledNote} /
 * {@code handledAt}, all {@code null} until an admin handles a {@code needs_review} attempt, UC-A18). Never contains
 * the QR payload.
 */
public record AdminPaymentResponse(
        Long id,
        Long orderId,
        String orderCode,
        Student student,
        String transactionRef,
        long amount,
        Instant qrExpiresAt,
        String status,
        Instant confirmedAt,
        Handler handledBy,
        String handledAction,
        String handledNote,
        Instant handledAt,
        Instant createdAt) {

    /** Buyer of the order. */
    public record Student(Long id, String fullName, String email) {
    }

    /** Admin who handled the attempt. */
    public record Handler(Long id, String fullName) {
    }
}
