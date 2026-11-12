package com.edumy.identity.entity;

import com.edumy.shared.jpa.CodeEnum;

/** {@code refresh_tokens.revoke_reason} (ck_refresh_tokens_reason, ADR-002; session flows in docs/ai/04 §4.1). */
public enum RevokeReason implements CodeEnum {

    LOGOUT("logout"),
    ROTATED("rotated"),
    REUSE_DETECTED("reuse_detected"),
    PASSWORD_CHANGED("password_changed"),
    PASSWORD_RESET("password_reset"),
    ACCOUNT_LOCKED("account_locked"),
    EXPIRED_CLEANUP("expired_cleanup");

    private final String code;

    RevokeReason(String code) {
        this.code = code;
    }

    @Override
    public String code() {
        return code;
    }
}
