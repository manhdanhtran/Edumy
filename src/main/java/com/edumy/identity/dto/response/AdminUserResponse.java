package com.edumy.identity.dto.response;

import java.time.Instant;
import java.util.List;

/**
 * One row of {@code GET /admin/users} (UC-A01): account, email verification flag, {@code status} ({@code active} /
 * {@code locked}) and the lower-case codes of the roles <b>active</b> in the database (revoked roles are not listed,
 * D-04). Never contains the password hash.
 */
public record AdminUserResponse(
        Long id,
        String email,
        String fullName,
        boolean emailVerified,
        String status,
        List<String> roles,
        Instant createdAt) {

    public AdminUserResponse {
        roles = roles == null ? List.of() : List.copyOf(roles);
    }
}
