package com.edumy.identity.service;

import java.time.Duration;
import java.util.Objects;

import com.edumy.identity.dto.response.AuthTokenResponse;

/**
 * Result of a login or refresh (docs/ai/04 §4.1): the response body, plus the raw refresh token and its lifetime,
 * which the controller puts in the {@code edumy_rt} cookie only. {@link #toString()} never shows either token (NFR-08).
 */
public record SessionTokens(AuthTokenResponse body, String refreshToken, Duration refreshTtl) {

    public SessionTokens {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(refreshToken, "refreshToken");
        Objects.requireNonNull(refreshTtl, "refreshTtl");
    }

    @Override
    public String toString() {
        return "SessionTokens[body=" + body + ", refreshToken=****, refreshTtl=" + refreshTtl + "]";
    }
}
