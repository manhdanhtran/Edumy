package com.edumy.identity.security;

import java.io.IOException;
import java.util.Objects;

import com.edumy.shared.error.ErrorCode;
import com.edumy.shared.error.ProblemResponder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * P13 hardening: when the token check itself cannot reach the database (session / user status lookups of
 * {@link JwtSessionValidator}), the caller must not get a misleading 401 nor a bare 500 — it gets 503
 * {@code SERVICE_UNAVAILABLE} as a ProblemDetail with the {@code traceId} (written by {@link ProblemResponder}).
 * Sits right before the bearer-token filter and catches what escapes it: a {@link DataAccessException} thrown by the
 * validator, or the {@link AuthenticationServiceException} Spring Security wraps it in. Exceptions of controllers never
 * reach it (they are handled by {@code GlobalExceptionHandler}). Logged with the stack trace, no token (NFR-08).
 */
final class AuthenticationUnavailableFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationUnavailableFilter.class);

    private final ProblemResponder problems;

    AuthenticationUnavailableFilter(ProblemResponder problems) {
        this.problems = Objects.requireNonNull(problems, "problems");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } catch (AuthenticationServiceException | DataAccessException e) {
            if (response.isCommitted()) {
                throw e;
            }
            log.error("Authentication could not be checked (database unavailable): path={}", request.getRequestURI(),
                    e);
            problems.respond(request, response, ErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    /** True when {@code exception} means "the token could not be checked" rather than "the token is bad". */
    static boolean isUnavailable(Throwable exception) {
        for (Throwable t = exception; t != null; t = t.getCause()) {
            if (t instanceof AuthenticationServiceException || t instanceof DataAccessException) {
                return true;
            }
        }
        return false;
    }
}
