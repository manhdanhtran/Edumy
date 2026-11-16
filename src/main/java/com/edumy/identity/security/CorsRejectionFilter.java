package com.edumy.identity.security;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import com.edumy.shared.error.ErrorCode;
import com.edumy.shared.error.ProblemResponder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * P13: a cross-origin request to {@code /api/**} from an origin outside {@code edumy.cors.allowed-origins} is refused
 * with 403 {@code ACCESS_DENIED} as a ProblemDetail (with {@code traceId}) instead of Spring's plain-text "Invalid CORS
 * request". Same decision as Spring's CORS processor, made one step earlier: same-origin requests (no {@code Origin},
 * or an {@code Origin} equal to the request's own scheme / host / port) pass, and nothing is refused while no origin is
 * configured (then no CORS mapping exists, as before). Allowed origins pass to Spring's {@code CorsFilter}, which adds
 * the CORS headers.
 */
final class CorsRejectionFilter extends OncePerRequestFilter {

    private final Set<String> allowedOrigins;
    private final ProblemResponder problems;

    CorsRejectionFilter(List<String> allowedOrigins, ProblemResponder problems) {
        this.allowedOrigins = allowedOrigins.stream().map(CorsRejectionFilter::normalize).collect(Collectors.toSet());
        this.problems = Objects.requireNonNull(problems, "problems");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return allowedOrigins.isEmpty() || !request.getRequestURI().startsWith("/api/")
                || !CorsUtils.isCorsRequest(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        if (!allowedOrigins.contains(normalize(origin))) {
            problems.respond(request, response, ErrorCode.ACCESS_DENIED);
            return;
        }
        chain.doFilter(request, response);
    }

    /** Origins compare case-insensitively, without a trailing slash (as {@code AuthOriginFilter}). */
    private static String normalize(String origin) {
        String value = origin == null ? "" : origin.strip().toLowerCase(Locale.ROOT);
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
