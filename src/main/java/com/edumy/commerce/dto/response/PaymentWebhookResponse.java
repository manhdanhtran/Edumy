package com.edumy.commerce.dto.response;

/** Acknowledgement of {@code POST /webhooks/payments/fake}: the provider only needs to know the result was received. */
public record PaymentWebhookResponse(boolean received, String transactionRef) {
}
