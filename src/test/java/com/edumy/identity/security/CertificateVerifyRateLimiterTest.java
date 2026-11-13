package com.edumy.identity.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Duration;
import java.time.Instant;

import com.edumy.shared.error.BusinessException;
import com.edumy.shared.error.ErrorCode;
import com.edumy.shared.error.ProblemResponder;
import com.edumy.shared.error.RetryAfterException;
import com.edumy.support.ManualClock;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** NFR-03 / docs/ai/04 §4.4: 30 req/min per remoteAddr on {@code GET /api/v1/catalog/certificates/*}. */
class CertificateVerifyRateLimiterTest {

    static final String VERIFY = "/api/v1/catalog/certificates/ABC123";

    final ManualClock clock = new ManualClock(Instant.parse("2026-11-13T08:00:00Z"));
    final ProblemResponder problems = mock(ProblemResponder.class);
    final CertificateVerifyRateLimiter limiter = new CertificateVerifyRateLimiter(clock, problems);

    @Test
    void thirtyAllowed_31stRejectedWith429RateLimitedAndRetryAfter_NFR03() throws Exception {
        for (int i = 0; i < 30; i++) {
            assertThat(passes("GET", VERIFY, "10.0.0.1", null)).as("request %d", i + 1).isTrue();
        }

        MockHttpServletRequest request = request("GET", VERIFY, "10.0.0.1", null);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        limiter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNull();
        ArgumentCaptor<BusinessException> error = ArgumentCaptor.forClass(BusinessException.class);
        verify(problems).respond(same(request), same(response), error.capture());
        assertThat(error.getValue()).isInstanceOfSatisfying(RetryAfterException.class, ex -> {
            assertThat(ex.getCode()).isEqualTo(ErrorCode.RATE_LIMITED);
            assertThat(ex.retryAfterSeconds()).isEqualTo(2); // greedy refill: one token every 2 s
        });
    }

    @Test
    void refill_greedyOneTokenPer2s_fullAfterOneMinute() throws Exception {
        exhaust("10.0.0.2");

        clock.advance(Duration.ofSeconds(2));
        assertThat(passes("GET", VERIFY, "10.0.0.2", null)).isTrue();
        assertThat(passes("GET", VERIFY, "10.0.0.2", null)).isFalse();

        clock.advance(Duration.ofMinutes(1));
        for (int i = 0; i < 30; i++) {
            assertThat(passes("GET", VERIFY, "10.0.0.2", null)).as("request %d", i + 1).isTrue();
        }
        assertThat(passes("GET", VERIFY, "10.0.0.2", null)).isFalse();
    }

    @Test
    void clockMovingBack_grantsNoTokens_refillResumesWhenClockPassesItsHighestReading() throws Exception {
        Instant start = clock.instant();
        exhaust("10.0.0.8");

        // wall-clock step back (NTP, or MutableClock reset in the IT context): time stands still for the bucket
        Instant back = start.minus(Duration.ofMinutes(5));
        clock.setInstant(back);
        assertThat(passes("GET", VERIFY, "10.0.0.8", null)).isFalse();
        clock.setInstant(back.plusSeconds(10));
        assertThat(passes("GET", VERIFY, "10.0.0.8", null)).isFalse();

        clock.setInstant(start.plusSeconds(2));
        assertThat(passes("GET", VERIFY, "10.0.0.8", null)).isTrue();
        assertThat(passes("GET", VERIFY, "10.0.0.8", null)).isFalse();
    }

    @Test
    void separateIps_separateBuckets() throws Exception {
        exhaust("10.0.0.3");

        assertThat(passes("GET", VERIFY, "10.0.0.4", null)).isTrue();
        assertThat(passes("GET", VERIFY, "10.0.0.3", null)).isFalse();
    }

    @Test
    void xForwardedFor_ignored_keyIsRemoteAddrOnly() throws Exception {
        for (int i = 0; i < 30; i++) {
            assertThat(passes("GET", VERIFY, "10.0.0.5", "203.0.113." + i)).isTrue();
        }
        // a new X-Forwarded-For value does not give a fresh bucket
        assertThat(passes("GET", VERIFY, "10.0.0.5", "198.51.100.7")).isFalse();
        // and spoofing another client's address in X-Forwarded-For does not consume that client's bucket
        assertThat(passes("GET", VERIFY, "10.0.0.6", "10.0.0.5")).isTrue();
    }

    @Test
    void otherRequests_notLimited() throws Exception {
        for (int i = 0; i < 40; i++) {
            assertThat(passes("GET", "/api/v1/catalog/courses", "10.0.0.7", null)).isTrue();
            assertThat(passes("POST", VERIFY, "10.0.0.7", null)).isTrue();
            assertThat(passes("GET", "/api/v1/catalog/certificates/ABC/extra", "10.0.0.7", null)).isTrue();
        }
        verifyNoInteractions(problems);
        assertThat(passes("GET", VERIFY, "10.0.0.7", null)).isTrue(); // the bucket was untouched
    }

    // ------------------------------------------------------------------------------------------------------ helpers

    private void exhaust(String ip) throws Exception {
        for (int i = 0; i < 30; i++) {
            assertThat(passes("GET", VERIFY, ip, null)).isTrue();
        }
        assertThat(passes("GET", VERIFY, ip, null)).isFalse();
    }

    /** True when the request reached the rest of the chain, false when the limiter answered it. */
    private boolean passes(String method, String uri, String remoteAddr, String forwardedFor) throws Exception {
        MockFilterChain chain = new MockFilterChain();
        limiter.doFilter(request(method, uri, remoteAddr, forwardedFor), new MockHttpServletResponse(), chain);
        if (chain.getRequest() == null) {
            verify(problems, atLeastOnce()).respond(any(), any(), any(BusinessException.class));
            return false;
        }
        return true;
    }

    private static MockHttpServletRequest request(String method, String uri, String remoteAddr, String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRemoteAddr(remoteAddr);
        if (forwardedFor != null) {
            request.addHeader("X-Forwarded-For", forwardedFor);
        }
        return request;
    }
}
