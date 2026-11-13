package com.edumy.identity.api;

import java.util.Objects;

/**
 * UC-G08 step 3: a new 30 min password-reset code was issued for an active account (older unused codes voided,
 * BR-34). {@code rawCode} is needed for the mail link; {@link #toString()} masks it (NFR-08).
 */
public record PasswordResetRequested(Long userId, String email, String fullName, String rawCode) {

    public PasswordResetRequested {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(email, "email");
        Objects.requireNonNull(fullName, "fullName");
        Objects.requireNonNull(rawCode, "rawCode");
    }

    @Override
    public String toString() {
        return "PasswordResetRequested[userId=" + userId + ", email=" + email + ", rawCode=****]";
    }
}
