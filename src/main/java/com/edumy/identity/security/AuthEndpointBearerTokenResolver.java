package com.edumy.identity.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpMethod;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
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
 * <p>P13: the public reads ({@code GET /api/v1/catalog/**}: lists, course page, preview lesson, certificate check) treat
 * a token that is expired, malformed, revoked or of a locked account as absent — the page is shown anonymously instead
 * of a 401 (a stale token in the SPA must never break a public page). A valid token still gives the viewer context.
 * The token is decoded once here for that decision (HMAC + cached session check); a database failure during that check is
 * not swallowed (503, {@link AuthenticationUnavailableFilter}). Every other path uses {@link DefaultBearerTokenResolver}.
 */
final class AuthEndpointBearerTokenResolver implements BearerTokenResolver {

    private static final RequestMatcher AUTH_ENDPOINTS = PathPatternRequestMatcher.pathPattern("/api/v1/auth/**");
    private static final RequestMatcher NEEDS_TOKEN = new OrRequestMatcher(
            PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/v1/auth/logout"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/v1/auth/email-verification/resend"));

    private static final RequestMatcher PUBLIC_READS =
            PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/api/v1/catalog/**");

    private final BearerTokenResolver delegate = new DefaultBearerTokenResolver();
    private final JwtDecoder decoder;

    AuthEndpointBearerTokenResolver(JwtDecoder decoder) {
        this.decoder = decoder;
    }

    @Override
    public String resolve(HttpServletRequest request) {
        if (AUTH_ENDPOINTS.matches(request) && !NEEDS_TOKEN.matches(request)) {
            return null; // treated as anonymous; these endpoints are permitAll
        }
        String token = delegate.resolve(request);
        if (token != null && PUBLIC_READS.matches(request)) {
            try {
                decoder.decode(token);
            } catch (JwtException e) {
                return null; // expired / invalid / ended session: anonymous on a public read
            }
        }
        return token;
    }
}
