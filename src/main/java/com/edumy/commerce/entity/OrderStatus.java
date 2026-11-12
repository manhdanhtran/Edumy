package com.edumy.commerce.entity;

import com.edumy.shared.jpa.CodeEnum;

/** {@code orders.status} (ck_orders_status). Transitions: docs/ai/02 §2.2 (SRS §3.4.2), enforced by {@link Order}. */
public enum OrderStatus implements CodeEnum {

    PENDING("pending"),
    PAID("paid"),
    CANCELLED("cancelled");

    private final String code;

    OrderStatus(String code) {
        this.code = code;
    }

    @Override
    public String code() {
        return code;
    }
}
