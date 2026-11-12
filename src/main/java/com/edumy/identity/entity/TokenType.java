package com.edumy.identity.entity;

import java.time.Duration;

import com.edumy.shared.jpa.CodeEnum;

/** {@code user_tokens.token_type} (ck_user_tokens_type) with its lifetime (BR-34: 24 h / 30 min). */
public enum TokenType implements CodeEnum {

    EMAIL_VERIFICATION("email_verification", Duration.ofHours(24)),
    PASSWORD_RESET("password_reset", Duration.ofMinutes(30));

    private final String code;
    private final Duration ttl;

    TokenType(String code, Duration ttl) {
        this.code = code;
        this.ttl = ttl;
    }

    @Override
    public String code() {
        return code;
    }

    /** BR-34: email verification 24 h, password reset 30 min. */
    public Duration ttl() {
        return ttl;
    }
}
