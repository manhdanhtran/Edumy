package com.edumy.authoring.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * UC-I13 step 1: email of the instructor to invite ({@code users.email} VARCHAR(255)); compared case-insensitively
 * and trimmed by {@code identity::api.UserQueryApi.findIdByEmail}.
 */
public record InviteCoInstructorRequest(
        @NotBlank @Email @Size(max = 255) String email) {

    public InviteCoInstructorRequest {
        email = email == null ? null : email.strip();
    }
}
