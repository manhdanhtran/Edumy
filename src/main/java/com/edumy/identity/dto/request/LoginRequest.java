package com.edumy.identity.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * UC-G07 {@code POST /auth/login}. No password policy here: accounts may hold passwords set under an older policy, and
 * a wrong password must get the generic 401, not a validation error. {@link #toString()} masks the password (NFR-08).
 */
public record LoginRequest(
        @NotBlank @Size(max = 255) String email,
        @NotBlank @Size(max = 255) String password) {

    @Override
    public String toString() {
        return "LoginRequest[email=" + email + ", password=****]";
    }
}
