package com.edumy.identity.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpMethod;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Bearer token lookup of the resource server. The public {@code /api/v1/auth/**} endpoints (login, refresh, register,
 * email confirmation, password forgot/reset) ignore the {@code Authorization} header: the SPA may still send an
 * expired access token there, which must not turn a login or a refresh into a 401. The two auth endpoints that act on
 * the caller's session ({@code POST /auth/logout}, {@code POST /auth/email-verification/resend}, Q2) still read it.
 * Every other path (catalog included) uses {@link DefaultBearerTokenResolver} unchanged.
 */
final class AuthEndpointBearerTokenResolver implements BearerTokenResolver {

    private static final RequestMatcher AUTH_ENDPOINTS = PathPatternRequestMatcher.pathPattern("/api/v1/auth/**");
    private static final RequestMatcher NEEDS_TOKEN = new OrRequestMatcher(
            PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/v1/auth/logout"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/v1/auth/email-verification/resend"));

    private final BearerTokenResolver delegate = new DefaultBearerTokenResolver();

    @Override
    public String resolve(HttpServletRequest request) {
        if (AUTH_ENDPOINTS.matches(request) && !NEEDS_TOKEN.matches(request)) {
            return null; // treated as anonymous; these endpoints are permitAll
        }
        return delegate.resolve(request);
    }
}
