package com.edumy.identity.api;

import java.util.Set;

import com.edumy.shared.security.RoleCode;

/**
 * Active roles of a user as stored in {@code user_roles} (BR-26: permissions are checked at action time from the
 * database, not from the up to 15 min old JWT {@code roles} claim; D-07: a revoked instructor loses editing rights at
 * once). Answers are cached 60 s per user ({@code identity.active-roles}) and evicted after commit on every grant /
 * revoke.
 */
public interface RoleQueryApi {

    /** Active role codes of {@code userId}; empty for an unknown user or one without an active role. Immutable. */
    Set<RoleCode> activeRoles(Long userId);

    /** {@code role} is in {@link #activeRoles(Long)}. */
    boolean hasActiveRole(Long userId, RoleCode role);
}
