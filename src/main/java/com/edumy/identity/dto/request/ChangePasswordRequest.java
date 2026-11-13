package com.edumy.identity.dto.request;

import com.edumy.shared.validation.StrongPassword;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * UC-G12 {@code PUT /me/password}. The current password gets no policy check (it may predate the policy; a wrong one
 * is 422 {@code PASSWORD_INCORRECT}); the new one must follow it. {@link #toString()} masks both (NFR-08).
 */
public record ChangePasswordRequest(
        @NotBlank @Size(max = 255) String currentPassword,
        @NotBlank @StrongPassword String newPassword) {

    @Override
    public String toString() {
        return "ChangePasswordRequest[currentPassword=****, newPassword=****]";
    }
}
