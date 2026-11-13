package com.edumy.identity.dto.request;

import com.edumy.shared.validation.StrongPassword;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * UC-G08 {@code POST /auth/password/reset}: the code from the mail link ({@code {frontend}/reset-password?token=…})
 * and the new password (the "type it twice" check is done by the SPA). {@link #toString()} masks both (NFR-08).
 */
public record ResetPasswordRequest(
        @NotBlank @Size(max = 100) String token,
        @NotBlank @StrongPassword String newPassword) {

    @Override
    public String toString() {
        return "ResetPasswordRequest[token=****, newPassword=****]";
    }
}
