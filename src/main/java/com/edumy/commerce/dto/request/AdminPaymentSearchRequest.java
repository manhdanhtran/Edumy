package com.edumy.commerce.dto.request;

import jakarta.validation.constraints.Pattern;

/**
 * Query parameters of UC-A14 {@code GET /admin/payments?status=&awaitingReview=}. {@code status}:
 * {@code pending|success|failed|expired|needs_review}, blank = every status, anything else is a field error (400
 * {@code VALIDATION_FAILED}). {@code awaitingReview=true} = the reconciliation queue of UC-A14 / UC-A18
 * ({@code needs_review} and not yet handled, oldest first); it overrides {@code status}.
 */
public record AdminPaymentSearchRequest(
        @Pattern(regexp = STATUS_CODES, message = "{edumy.payment.status.invalid}") String status,
        Boolean awaitingReview) {

    /** {@code payment_transactions.status} codes (ck_payment_status, {@code PaymentStatus}). */
    public static final String STATUS_CODES = "pending|success|failed|expired|needs_review";

    public AdminPaymentSearchRequest {
        status = status == null || status.isBlank() ? null : status.strip();
    }

    public boolean onlyAwaitingReview() {
        return Boolean.TRUE.equals(awaitingReview);
    }
}
