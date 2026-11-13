package com.edumy.identity.dto.request;

import com.edumy.shared.validation.StrongPassword;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * UC-G05 {@code POST /auth/register}. The email is stripped here so the format check sees what will be stored; the
 * service lower-cases it ({@code User.normalizeEmail}). {@link #toString()} masks the password (NFR-08).
 */
public record RegisterRequest(
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @StrongPassword String password,
        @NotBlank @Size(max = 100, message = "{edumy.user.full-name.size}") String fullName) {

    public RegisterRequest {
        email = email == null ? null : email.strip();
    }

    @Override
    public String toString() {
        return "RegisterRequest[email=" + email + ", password=****, fullName=" + fullName + "]";
    }
}
