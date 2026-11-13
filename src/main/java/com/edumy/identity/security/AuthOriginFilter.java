package com.edumy.identity.security;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import com.edumy.shared.error.ErrorCode;
import com.edumy.shared.error.ProblemResponder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * CSRF guard of the two cookie-driven endpoints (docs/ai/04 §4.5): {@code POST /auth/refresh} and
 * {@code POST /auth/logout} carrying an {@code Origin} header that is not in {@code edumy.cors.allowed-origins} get 403
 * {@code ACCESS_DENIED} (ProblemDetail through {@link ProblemResponder}). Without an {@code Origin} header the request
 * passes: non-browser clients send none, and browsers do not send the {@code SameSite=Strict} cookie cross-site.
 * <p>Registered only inside the Spring Security chain by {@link SecurityConfig} (not a {@code @Component}, so Boot does
 * not register it a second time as a servlet filter).
 */
final class AuthOriginFilter extends OncePerRequestFilter {

    private static final RequestMatcher COOKIE_ENDPOINTS = new OrRequestMatcher(
            PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/v1/auth/refresh"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/v1/auth/logout"));

    private final Set<String> allowedOrigins;
    private final ProblemResponder problems;

    AuthOriginFilter(List<String> allowedOrigins, ProblemResponder problems) {
        this.allowedOrigins = allowedOrigins.stream().map(AuthOriginFilter::normalize).collect(Collectors.toSet());
        this.problems = problems;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !COOKIE_ENDPOINTS.matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        if (origin != null && !allowedOrigins.contains(normalize(origin))) {
            problems.respond(request, response, ErrorCode.ACCESS_DENIED);
            return;
        }
        chain.doFilter(request, response);
    }

    /** Origins compare case-insensitively (scheme and host are case-insensitive), without a trailing slash. */
    private static String normalize(String origin) {
        String value = origin.strip().toLowerCase(Locale.ROOT);
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
