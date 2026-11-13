package com.edumy.identity.dto.response;

import java.time.Instant;

/**
 * Body of {@code /auth/login} and {@code /auth/refresh} (docs/ai/04 §4.1): the access JWT and its expiry. The refresh
 * token never appears in a body, only in the {@code edumy_rt} cookie. {@link #toString()} masks the token (NFR-08).
 */
public record AuthTokenResponse(String accessToken, Instant expiresAt) {

    @Override
    public String toString() {
        return "AuthTokenResponse[accessToken=****, expiresAt=" + expiresAt + "]";
    }
}
