package com.edumy.identity.api;

import java.time.Instant;
import java.util.Objects;

/**
 * The password of an account changed, by reset (UC-G08 step 6) or by the user (UC-G12): the notification module sends
 * the "your password was changed" notice. Carries no secret.
 */
public record PasswordChanged(Long userId, String email, String fullName, Instant occurredAt) {

    public PasswordChanged {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(email, "email");
        Objects.requireNonNull(fullName, "fullName");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
