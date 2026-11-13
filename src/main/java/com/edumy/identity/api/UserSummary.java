package com.edumy.identity.api;

/**
 * Public facts about a user for other modules (docs/ai/01 §1.9): names shown next to courses, invitations, Q&amp;A…
 * {@code status} is the lower-case code of {@code users.status} ({@code active} / {@code locked}). Never contains the
 * password hash or roles (roles: {@link RoleQueryApi}).
 */
public record UserSummary(Long id, String fullName, String email, String status) {
}
