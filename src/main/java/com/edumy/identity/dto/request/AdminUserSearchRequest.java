package com.edumy.identity.dto.request;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Query parameters of UC-A01 {@code GET /admin/users?q=&status=&role=} (bound from the query string; paging is the
 * separate {@code Pageable}). Every filter is optional; an absent or blank parameter means "no filter". {@code q}
 * (trimmed): part of the email or of the full name. {@code status} / {@code role}: lower-case codes as returned in
 * {@code AdminUserResponse}; any other value is a field error (400 {@code VALIDATION_FAILED}).
 */
public record AdminUserSearchRequest(
        @Size(max = 255) String q,
        @Pattern(regexp = STATUS_CODES, message = "{edumy.user.status.invalid}") String status,
        @Pattern(regexp = ROLE_CODES, message = "{edumy.user.role.invalid}") String role) {

    /** {@code users.status} codes (ck_users_status, {@code UserStatus}). */
    public static final String STATUS_CODES = "active|locked";
    /** {@code roles.code} codes (ck_roles_code, {@code RoleCode}). */
    public static final String ROLE_CODES = "student|instructor|admin";

    public AdminUserSearchRequest {
        q = blankToNull(q);
        status = blankToNull(status);
        role = blankToNull(role);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
