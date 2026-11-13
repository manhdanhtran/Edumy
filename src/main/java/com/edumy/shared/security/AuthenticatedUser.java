package com.edumy.shared.security;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.edumy.shared.error.BusinessException;
import com.edumy.shared.error.ErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The caller of the current request as a {@link CurrentUser}, read from the access JWT in the
 * {@link SecurityContextHolder} (docs/ai/04 §4.1). For access beans and services; controllers take
 * {@code @CurrentUserId Long userId}. JWT roles may be up to 15 min stale: instructor actions re-check them through
 * {@code RoleQueryApi} (BR-26).
 */
public final class AuthenticatedUser {

    /** JWT claim with the active role codes at issue time, lower-case as {@link RoleCode#code()}. */
    public static final String ROLES_CLAIM = "roles";
    /** JWT claim with the session id (= {@code refresh_tokens.family_id}). */
    public static final String SESSION_ID_CLAIM = "sid";

    private AuthenticatedUser() {
    }

    /** The authenticated caller, or empty for an anonymous request or a token that is not one of ours. */
    public static Optional<CurrentUser> current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof Jwt jwt) {
            return from(jwt);
        }
        return Optional.empty();
    }

    /** The authenticated caller; {@link ErrorCode#UNAUTHENTICATED} (401) when there is none. */
    public static CurrentUser require() {
        return current().orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
    }

    /** {@code sub} → id, {@code roles} → known {@link RoleCode}s (unknown codes ignored), {@code sid}. */
    public static Optional<CurrentUser> from(Jwt jwt) {
        String subject = jwt.getSubject();
        String sessionId = jwt.getClaimAsString(SESSION_ID_CLAIM);
        if (subject == null || sessionId == null) {
            return Optional.empty();
        }
        long id;
        try {
            id = Long.parseLong(subject);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        return Optional.of(new CurrentUser(id, roles(jwt), sessionId));
    }

    private static Set<RoleCode> roles(Jwt jwt) {
        List<String> codes = jwt.hasClaim(ROLES_CLAIM) ? jwt.getClaimAsStringList(ROLES_CLAIM) : List.of();
        Set<RoleCode> roles = EnumSet.noneOf(RoleCode.class);
        for (RoleCode role : RoleCode.values()) {
            if (codes != null && codes.contains(role.code())) {
                roles.add(role);
            }
        }
        return roles;
    }
}
