package com.edumy.commerce.integration;

import java.time.Instant;

/** Answer of {@link PaymentGateway#query}: the provider's view of one attempt. */
public sealed interface PaymentLookup {

    /** Money arrived: {@code amount} VND at {@code paidAt} (provider time, may be null). */
    record Paid(long amount, Instant paidAt) implements PaymentLookup {
    }

    /** The provider reports the payment as failed (UC-S05 A2). */
    record Failed() implements PaymentLookup {
    }

    /** Not paid (still open or expired at the provider): the attempt may be closed (UC-S05 A1, UC-S16 step 2). */
    record NotPaid() implements PaymentLookup {
    }
}
