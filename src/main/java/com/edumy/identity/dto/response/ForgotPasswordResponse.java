package com.edumy.identity.dto.response;

/**
 * UC-G08 step 2 / D-03: the one answer of {@code POST /auth/password/forgot}, whatever happened (known, unknown or
 * locked account, cooldown).
 */
public record ForgotPasswordResponse(String message) {

    /** UC-G08 step 2, docs/ai/04 §4.2. */
    public static final String GENERIC_MESSAGE = "Nếu email tồn tại, hướng dẫn đã được gửi";

    public static ForgotPasswordResponse generic() {
        return new ForgotPasswordResponse(GENERIC_MESSAGE);
    }
}
