package com.edumy.commerce.dto.request;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * UC-S05 step 5 {@code POST /webhooks/payments/fake}: the provider's result. Verified by T2 against reference, order
 * and amount (BR-14); {@code status} = {@code success|failed}; {@code paidAt} = provider time of the payment (optional).
 * <b>OpenAPI schema only</b> (P11-a): the controller reads the raw body; {@code integration.FakeQrPaymentGateway} checks
 * the HMAC signature and parses the same fields into its own record (services never take HTTP records, SC-M4).
 */
public record PaymentWebhookRequest(
        @NotBlank @Size(max = 100) String transactionRef,
        @NotBlank @Size(max = 30) String orderCode,
        @NotNull @Positive Long amount,
        @NotBlank @Pattern(regexp = "success|failed") String status,
        Instant paidAt) {
}
