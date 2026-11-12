package com.edumy.commerce.entity;

import com.edumy.shared.jpa.CodeEnum;

/**
 * {@code payment_transactions.status} (ck_payment_status). Transitions: docs/ai/02 §2.2 (SRS §3.4.3), enforced by
 * {@link PaymentTransaction}.
 */
public enum PaymentStatus implements CodeEnum {

    PENDING("pending"),
    SUCCESS("success"),
    FAILED("failed"),
    EXPIRED("expired"),
    NEEDS_REVIEW("needs_review");

    private final String code;

    PaymentStatus(String code) {
        this.code = code;
    }

    @Override
    public String code() {
        return code;
    }
}
