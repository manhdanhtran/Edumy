package com.edumy.support;

import java.util.Arrays;
import java.util.List;

import com.edumy.shared.security.AuthenticatedUser;
import com.edumy.shared.security.RoleCode;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * For unit tests of access beans and services that read {@link AuthenticatedUser}: puts an authenticated JWT caller
 * into the {@link SecurityContextHolder}, as the resource-server filter would. Always {@link #clear()} in
 * {@code @AfterEach}.
 */
public final class TestSecurityContext {

    private TestSecurityContext() {
    }

    /** Caller {@code userId} whose access token claims {@code jwtRoles} (the database may say otherwise). */
    public static void authenticate(Long userId, RoleCode... jwtRoles) {
        List<String> codes = Arrays.stream(jwtRoles).map(RoleCode::code).toList();
        Jwt jwt = Jwt.withTokenValue("unit-test-token")
                .header("alg", "HS256")
                .subject(String.valueOf(userId))
                .claim(AuthenticatedUser.SESSION_ID_CLAIM, "unit-test-sid")
                .claim(AuthenticatedUser.ROLES_CLAIM, codes)
                .build();
        List<SimpleGrantedAuthority> authorities = Arrays.stream(jwtRoles)
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name()))
                .toList();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, authorities));
    }

    public static void clear() {
        SecurityContextHolder.clearContext();
    }
}
