package com.edumy.commerce.integration;

import java.time.Instant;
import java.util.Map;

/**
 * Port to the QR payment provider (SRS §7 "Dịch vụ thanh toán QR": Edumy sends reference, amount, QR expiry; receives
 * QR data, results and lookups). Services depend on this interface only (docs/ai/01 §1.4). Implementations:
 * {@link FakeQrPaymentGateway} (every profile but {@code payos}); a payOS adapter will be added under the profile
 * {@code payos} without touching the services (ADR-011).
 * <p>The {@code reference} is always the <b>transaction reference</b> ({@code payment_transactions.transaction_ref},
 * one per QR attempt, D-27), never the order code. Implementations must not log secrets or signatures (NFR-08) and a
 * real adapter must call the provider with connect / read timeouts of at most {@code edumy.payment.connect-timeout} /
 * {@code read-timeout}: the callers hold an order row lock while they wait.
 */
public interface PaymentGateway {

    /**
     * UC-S05 step 1: asks the provider for a QR of {@code amount} VND valid until {@code expiresAt}.
     *
     * @throws PaymentGatewayException provider unreachable or refusing (UC-S05 E1)
     */
    QrCode createQr(String reference, long amount, Instant expiresAt);

    /**
     * Active lookup of an attempt (UC-S05 step 5 / A1, UC-S04 step 2, UC-S16 step 1).
     *
     * @throws PaymentGatewayException provider unreachable (UC-S16 E2: keep everything, retry later)
     */
    PaymentLookup query(String reference);

    /**
     * Verifies the provider's notification on the RAW body before parsing it, then parses it.
     *
     * @param headers request headers, keys case-insensitive
     * @throws com.edumy.shared.error.BusinessException {@code UNAUTHENTICATED} (signature missing / wrong),
     *                                                  {@code MALFORMED_REQUEST} / {@code VALIDATION_FAILED} (signed
     *                                                  but unreadable / invalid body)
     */
    PaymentResult verifyWebhook(byte[] rawBody, Map<String, String> headers);
}
