package com.edumy.shared.security;

import java.util.Set;

/**
 * Authenticated caller as seen from the access JWT ({@code sub}, {@code roles}, {@code sid}; docs/ai/04 §4.1).
 * Roles here are those at token issue time; sensitive actions re-check via {@code RoleQueryApi} (BR-26).
 */
public record CurrentUser(Long id, Set<RoleCode> roles, String sessionId) {

    public CurrentUser {
        roles = Set.copyOf(roles);
    }

    public boolean hasRole(RoleCode role) {
        return roles.contains(role);
    }
}
