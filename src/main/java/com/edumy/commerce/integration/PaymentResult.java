package com.edumy.commerce.integration;

import java.time.Instant;
import java.util.Objects;

/**
 * A payment result to apply (T2): from a verified webhook or from a lookup ({@link PaymentLookup.Paid} /
 * {@link PaymentLookup.Failed}). {@code orderCode} is set only when the provider echoes it (the fake webhook does;
 * a lookup does not); when present it must match the attempt's order (BR-14, AC-19).
 */
public record PaymentResult(String reference, String orderCode, long amount, boolean success, Instant paidAt) {

    public PaymentResult {
        Objects.requireNonNull(reference, "reference");
    }

    /** The lookup answer for {@code reference} as a result; {@code null} for {@link PaymentLookup.NotPaid}. */
    public static PaymentResult fromLookup(String reference, PaymentLookup lookup) {
        return switch (lookup) {
            case PaymentLookup.Paid paid -> new PaymentResult(reference, null, paid.amount(), true, paid.paidAt());
            case PaymentLookup.Failed failed -> new PaymentResult(reference, null, 0, false, null);
            case PaymentLookup.NotPaid notPaid -> null;
        };
    }
}
