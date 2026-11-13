package com.edumy.identity.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** UC-G08 {@code POST /auth/password/forgot}. The email is stripped here; the service lower-cases it. */
public record ForgotPasswordRequest(@NotBlank @Email @Size(max = 255) String email) {

    public ForgotPasswordRequest {
        email = email == null ? null : email.strip();
    }
}
