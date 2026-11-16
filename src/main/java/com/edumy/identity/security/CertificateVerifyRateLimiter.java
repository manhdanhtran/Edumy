package com.edumy.identity.security;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

import com.edumy.shared.error.ErrorCode;
import com.edumy.shared.error.ProblemResponder;
import com.edumy.shared.error.RetryAfterException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TimeMeter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * NFR-03 guard of the public certificate verification {@code GET /api/v1/catalog/certificates/{code}} (docs/ai/04
 * §4.4): a Bucket4j token bucket per client IP, capacity {@value #CAPACITY}, refilled <b>greedily</b> at
 * {@value #CAPACITY} tokens per minute (one token every 2 s). Greedy refill is Bucket4j's default: a client may burst
 * 30 requests, then gets one more every 2 s (long-run average 30/min) instead of waiting for a whole-minute reset, and
 * clients are not released together at a minute boundary. Over the limit → 429 {@code RATE_LIMITED} ProblemDetail
 * (through {@link ProblemResponder}) with {@code Retry-After} = seconds until the next token, rounded up.
 * <p>The client IP is {@link HttpServletRequest#getRemoteAddr()} only; {@code X-Forwarded-For} is never read (it is
 * client-controlled).
 * TODO(deploy): behind a reverse proxy every client shares the proxy's address, so production must set
 * {@code server.forward-headers-strategy} (NATIVE or FRAMEWORK) trusting only that proxy, so that remoteAddr becomes
 * the real client IP. See ADR-002 "Abuse protection — known limitations".
 * <p>Buckets live in a bounded Caffeine cache ({@value #MAX_BUCKETS} IPs, expire {@link #IDLE_EXPIRY} after last use;
 * an idle bucket is full again after one minute, so expiry never grants extra tokens). Bucket4j time and the cache
 * ticker read the injected {@link Clock} (never going backwards). In-memory, single instance only (ADR-002).
 * <p>Registered only inside the Spring Security chain by {@link SecurityConfig}, before token processing (not a
 * {@code @Component}, so Boot does not register it a second time as a servlet filter).
 */
final class CertificateVerifyRateLimiter extends OncePerRequestFilter {

    static final long CAPACITY = 30;
    static final Duration REFILL_PERIOD = Duration.ofMinutes(1);
    static final Duration IDLE_EXPIRY = Duration.ofMinutes(5);
    static final long MAX_BUCKETS = 100_000;

    private static final RequestMatcher CERTIFICATE_VERIFY =
            PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/api/v1/catalog/certificates/*");

    private final ProblemResponder problems;
    private final TimeMeter timeMeter;
    private final Cache<String, Bucket> buckets;

    CertificateVerifyRateLimiter(Clock clock, ProblemResponder problems) {
        Objects.requireNonNull(clock, "clock");
        this.problems = Objects.requireNonNull(problems, "problems");
        this.timeMeter = new ClockTimeMeter(clock);
        this.buckets = Caffeine.newBuilder()
                .ticker(timeMeter::currentTimeNanos)
                .expireAfterAccess(IDLE_EXPIRY)
                .maximumSize(MAX_BUCKETS)
                .build();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !CERTIFICATE_VERIFY.matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Bucket bucket = buckets.get(request.getRemoteAddr(), ip -> newBucket());
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (!probe.isConsumed()) {
            problems.respond(request, response, new RetryAfterException(ErrorCode.RATE_LIMITED,
                    Duration.ofNanos(probe.getNanosToWaitForRefill())));
            return;
        }
        chain.doFilter(request, response);
    }

    private Bucket newBucket() {
        return Bucket.builder()
                .addLimit(limit -> limit.capacity(CAPACITY).refillGreedy(CAPACITY, REFILL_PERIOD))
                .withCustomTimePrecision(timeMeter)
                .build();
    }
}
