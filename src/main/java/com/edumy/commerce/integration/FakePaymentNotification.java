package com.edumy.commerce.integration;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Wire format of the fake provider's webhook ({@code POST /webhooks/payments/fake}), parsed by
 * {@link FakeQrPaymentGateway} after the HMAC signature over the raw body was checked. {@code transactionRef} = the
 * attempt's reference (what {@link PaymentGateway#createQr} received); {@code orderCode} is echoed for the BR-14 order
 * check; {@code status} = {@code success|failed}; {@code paidAt} optional. The OpenAPI schema of the endpoint is
 * {@code dto.request.PaymentWebhookRequest} (same fields).
 */
record FakePaymentNotification(
        @NotBlank @Size(max = 100) String transactionRef,
        @NotBlank @Size(max = 30) String orderCode,
        @NotNull @Positive Long amount,
        @NotBlank @Pattern(regexp = "success|failed") String status,
        Instant paidAt) {
}
