package com.edumy.commerce.entity;

import com.edumy.shared.jpa.CodeEnum;

/**
 * {@code payment_transactions.handled_action} (ck_payment_action): admin decision on a needs_review transaction
 * (UC-A18, D-29).
 */
public enum HandledAction implements CodeEnum {

    ACTIVATED("activated"),
    REFUND_OUTSIDE("refund_outside");

    private final String code;

    HandledAction(String code) {
        this.code = code;
    }

    @Override
    public String code() {
        return code;
    }
}
