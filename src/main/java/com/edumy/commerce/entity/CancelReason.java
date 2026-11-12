package com.edumy.commerce.entity;

import com.edumy.shared.jpa.CodeEnum;

/**
 * {@code orders.cancel_reason} (ck_orders_cancel_reason): cancelled by the student (UC-S16), replaced by a new order
 * (D-24) or expired after 24 h (BR-29).
 */
public enum CancelReason implements CodeEnum {

    USER("user"),
    REPLACED("replaced"),
    EXPIRED("expired");

    private final String code;

    CancelReason(String code) {
        this.code = code;
    }

    @Override
    public String code() {
        return code;
    }
}
