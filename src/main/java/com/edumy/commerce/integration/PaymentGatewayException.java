package com.edumy.commerce.integration;

/**
 * The provider could not be reached or refused the call (timeout, 5xx, network). Services answer 503
 * {@code PAYMENT_PROVIDER_UNAVAILABLE} and change nothing (UC-S05 E1, UC-S16 E2); schedulers keep the row for the next
 * run. The message must not contain secrets (NFR-08).
 */
public class PaymentGatewayException extends RuntimeException {

    public PaymentGatewayException(String message) {
        super(message);
    }

    public PaymentGatewayException(String message, Throwable cause) {
        super(message, cause);
    }
}
