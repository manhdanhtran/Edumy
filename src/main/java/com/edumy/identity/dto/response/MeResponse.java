package com.edumy.identity.dto.response;

import java.time.Instant;
import java.util.List;

/**
 * {@code GET /me}, {@code PATCH /me}, {@code POST /me/instructor-registration} (UC-G09, UC-G13). {@code roles} are the
 * lower-case codes of the roles active <b>in the database now</b> ({@code student}, {@code instructor},
 * {@code admin}); the access token may still carry older roles for up to 15 min until the next refresh (BR-26).
 */
public record MeResponse(
        Long id,
        String email,
        String fullName,
        boolean emailVerified,
        List<String> roles,
        Instant createdAt) {

    public MeResponse {
        roles = roles == null ? List.of() : List.copyOf(roles);
    }
}
