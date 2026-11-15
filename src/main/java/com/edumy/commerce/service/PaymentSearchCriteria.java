package com.edumy.commerce.service;

/**
 * UC-A14 filters for {@link PaymentQueryService#search} (SC-M4). {@code status}: a {@code payment_transactions.status}
 * code already validated by the controller, {@code null} = every status; {@code awaitingReview}: the unhandled
 * reconciliation queue (UC-A18), overrides {@code status}.
 */
public record PaymentSearchCriteria(String status, boolean awaitingReview) {
}
