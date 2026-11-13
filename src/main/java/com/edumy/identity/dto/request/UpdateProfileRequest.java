package com.edumy.identity.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** UC-G09 {@code PATCH /me}: only the full name can change (the email is read-only, D-06). Stored trimmed. */
public record UpdateProfileRequest(
        @NotBlank @Size(max = 100, message = "{edumy.user.full-name.size}") String fullName) {
}
