package com.edumy.identity.api;

import java.util.Objects;

/**
 * UC-G05: an account was created (published inside the registration transaction; the notification module sends the
 * welcome + verification mail after commit, so a mail failure never undoes the registration, UC-G05 E3).
 * {@code rawVerificationCode} is the emailed 24 h code (only its SHA-256 is stored, BR-34), needed to build the link;
 * {@link #toString()} masks it (NFR-08).
 */
public record UserRegistered(Long userId, String email, String fullName, String rawVerificationCode) {

    public UserRegistered {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(email, "email");
        Objects.requireNonNull(fullName, "fullName");
        Objects.requireNonNull(rawVerificationCode, "rawVerificationCode");
    }

    @Override
    public String toString() {
        return "UserRegistered[userId=" + userId + ", email=" + email + ", rawVerificationCode=****]";
    }
}
