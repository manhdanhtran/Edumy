package com.edumy.identity.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * UC-G06 {@code POST /auth/email-verification/confirm}: the code from the mail link
 * ({@code {frontend}/verify-email?token=…}). Public, no session needed. {@link #toString()} masks it (NFR-08).
 */
public record VerifyEmailRequest(@NotBlank @Size(max = 100) String token) {

    @Override
    public String toString() {
        return "VerifyEmailRequest[token=****]";
    }
}
