package com.edumy.identity.api;

import java.util.Objects;

/**
 * UC-G06 resend: a new 24 h email-verification code was issued (older unused codes voided, BR-34). {@code rawCode} is
 * needed for the mail link; {@link #toString()} masks it (NFR-08).
 */
public record EmailVerificationRequested(Long userId, String email, String fullName, String rawCode) {

    public EmailVerificationRequested {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(email, "email");
        Objects.requireNonNull(fullName, "fullName");
        Objects.requireNonNull(rawCode, "rawCode");
    }

    @Override
    public String toString() {
        return "EmailVerificationRequested[userId=" + userId + ", email=" + email + ", rawCode=****]";
    }
}
