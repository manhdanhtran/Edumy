package com.edumy.identity.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.edumy.identity.config.AuthRateLimitProperties;
import com.edumy.shared.error.BusinessException;
import com.edumy.shared.error.ErrorCode;
import com.edumy.shared.error.ProblemResponder;
import com.edumy.shared.error.RetryAfterException;
import com.edumy.support.ManualClock;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

/**
 * P13 security hardening, filter by filter: per-IP limits of register / forgot / reset (ADR-002), 503 when the token
 * check cannot reach the database, 403 ProblemDetail for a foreign CORS origin.
 */
class AuthHardeningFiltersTest {

    final ManualClock clock = new ManualClock(Instant.parse("2026-11-16T08:00:00Z"));
    final ProblemResponder problems = mock(ProblemResponder.class);

    static MockHttpServletRequest post(String path, String ip) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setRemoteAddr(ip);
        return request;
    }

    // ---- AuthEndpointRateLimiter

    final AuthEndpointRateLimiter limiter = new AuthEndpointRateLimiter(clock, problems, AuthRateLimitProperties.defaults());

    boolean passes(String path, String ip) throws Exception {
        MockFilterChain chain = new MockFilterChain();
        limiter.doFilter(post(path, ip), new MockHttpServletResponse(), chain);
        return chain.getRequest() != null;
    }

    @Test
    void register_tenPerHourPerIp_eleventh429WithRetryAfter() throws Exception {
        for (int i = 0; i < 10; i++) {
            assertThat(passes("/api/v1/auth/register", "10.1.0.1")).as("request %d", i + 1).isTrue();
        }
        MockHttpServletRequest request = post("/api/v1/auth/register", "10.1.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        limiter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNull();
        ArgumentCaptor<BusinessException> error = ArgumentCaptor.forClass(BusinessException.class);
        verify(problems).respond(same(request), same(response), error.capture());
        assertThat(error.getValue()).isInstanceOfSatisfying(RetryAfterException.class, ex -> {
            assertThat(ex.getCode()).isEqualTo(ErrorCode.RATE_LIMITED);
            assertThat(ex.retryAfterSeconds()).isEqualTo(360); // greedy: one token every 6 minutes
        });
        assertThat(passes("/api/v1/auth/register", "10.1.0.2")).isTrue(); // another IP has its own bucket
    }

    @Test
    void forgot_fivePer15Minutes_reset_tenPer15Minutes_separateBuckets_refill() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertThat(passes("/api/v1/auth/password/forgot", "10.2.0.1")).isTrue();
        }
        assertThat(passes("/api/v1/auth/password/forgot", "10.2.0.1")).isFalse();
        for (int i = 0; i < 10; i++) {
            assertThat(passes("/api/v1/auth/password/reset", "10.2.0.1")).isTrue(); // not the forgot bucket
        }
        assertThat(passes("/api/v1/auth/password/reset", "10.2.0.1")).isFalse();

        clock.advance(Duration.ofMinutes(15));
        assertThat(passes("/api/v1/auth/password/forgot", "10.2.0.1")).isTrue();
        assertThat(passes("/api/v1/auth/password/reset", "10.2.0.1")).isTrue();
    }

    @Test
    void otherEndpointsAreNotLimited() throws Exception {
        for (int i = 0; i < 50; i++) {
            assertThat(passes("/api/v1/auth/login", "10.3.0.1")).isTrue(); // login has its own limiter (NFR-03)
        }
    }

    // ---- AuthenticationUnavailableFilter

    @Test
    void databaseFailureWhileCheckingTheToken_503ServiceUnavailable_notA401() throws Exception {
        AuthenticationUnavailableFilter filter = new AuthenticationUnavailableFilter(problems);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/cart");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain failing = (req, res) -> {
            throw new AuthenticationServiceException("check failed",
                    new DataAccessResourceFailureException("db down"));
        };

        filter.doFilter(request, response, failing);

        verify(problems).respond(same(request), same(response), eq(ErrorCode.SERVICE_UNAVAILABLE));
        FilterChain rawDbError = (req, res) -> {
            throw new DataAccessResourceFailureException("db down");
        };
        MockHttpServletResponse second = new MockHttpServletResponse();
        filter.doFilter(request, second, rawDbError);
        verify(problems).respond(same(request), same(second), eq(ErrorCode.SERVICE_UNAVAILABLE));
    }

    @Test
    void isUnavailable_onlyForCheckFailures_notForBadTokens() {
        assertThat(AuthenticationUnavailableFilter.isUnavailable(new AuthenticationServiceException("x"))).isTrue();
        assertThat(AuthenticationUnavailableFilter.isUnavailable(
                new RuntimeException(new DataAccessResourceFailureException("x")))).isTrue();
        assertThat(AuthenticationUnavailableFilter.isUnavailable(new InvalidBearerTokenException("bad"))).isFalse();
    }

    @Test
    void otherExceptionsAreNotTurnedInto503() {
        AuthenticationUnavailableFilter filter = new AuthenticationUnavailableFilter(problems);
        FilterChain failing = (req, res) -> {
            throw new IllegalStateException("bug");
        };
        assertThatThrownBy(() -> filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), failing))
                .isInstanceOf(IllegalStateException.class);
        verify(problems, never()).respond(any(), any(), any(ErrorCode.class));
    }

    // ---- CorsRejectionFilter

    static MockHttpServletRequest cors(String origin) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/catalog/courses");
        request.setServerName("api.edumy.test");
        request.setScheme("https");
        request.setServerPort(443);
        if (origin != null) {
            request.addHeader("Origin", origin);
        }
        return request;
    }

    @Test
    void foreignOrigin_403ProblemDetail_allowedOrSameOriginPass() throws Exception {
        CorsRejectionFilter filter = new CorsRejectionFilter(List.of("https://app.edumy.test"), problems);

        MockHttpServletRequest foreign = cors("https://evil.example.com");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain blocked = new MockFilterChain();
        filter.doFilter(foreign, response, blocked);
        assertThat(blocked.getRequest()).isNull();
        verify(problems).respond(same(foreign), same(response), eq(ErrorCode.ACCESS_DENIED));

        for (String passing : new String[] {"https://APP.edumy.test/", "https://api.edumy.test", null}) {
            MockFilterChain chain = new MockFilterChain();
            filter.doFilter(cors(passing), new MockHttpServletResponse(), chain);
            assertThat(chain.getRequest()).as(String.valueOf(passing)).isNotNull();
        }
    }

    @Test
    void noOriginConfigured_nothingRefused() throws Exception {
        CorsRejectionFilter filter = new CorsRejectionFilter(List.of(), problems);
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(cors("https://evil.example.com"), new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).isNotNull();
    }
}
