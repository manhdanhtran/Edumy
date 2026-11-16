package com.edumy.identity.security;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

import com.edumy.identity.config.AuthRateLimitProperties;
import com.edumy.shared.error.ErrorCode;
import com.edumy.shared.error.ProblemResponder;
import com.edumy.shared.error.RetryAfterException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * P13 / ADR-002 abuse protection of the anonymous account endpoints, per client IP (Bucket4j in memory, like
 * {@link CertificateVerifyRateLimiter}; same IP rule: {@code remoteAddr} only, never {@code X-Forwarded-For}):
 * <ul>
 *   <li>{@code POST /auth/register}: 10 per hour by default (mass account creation, mail bombing);</li>
 *   <li>{@code POST /auth/password/forgot}: 5 per 15 minutes (mail bombing of one victim from
 *   one place; the per-account 60 s resend rule stays, D-03);</li>
 *   <li>{@code POST /auth/password/reset}: 10 per 15 minutes (guessing codes; the code is also
 *   checked before BCrypt, so a guess costs no hash).</li>
 * </ul>
 * Values: {@link AuthRateLimitProperties}. Greedy refill over the period; over the limit → 429 {@code RATE_LIMITED} + {@code Retry-After}, before any token or
 * body is read. One bucket per (endpoint, IP) in a bounded Caffeine cache; single instance only (ADR-002 limitation).
 */
final class AuthEndpointRateLimiter extends OncePerRequestFilter {

    static final long MAX_BUCKETS = 100_000;

    /** One limited endpoint. */
    record Limit(String name, RequestMatcher matcher, long capacity, Duration period) {
    }

    private final List<Limit> limits;
    private final ProblemResponder problems;
    private final ClockTimeMeter timeMeter;
    private final Cache<String, Bucket> buckets;

    AuthEndpointRateLimiter(Clock clock, ProblemResponder problems, AuthRateLimitProperties properties) {
        Objects.requireNonNull(clock, "clock");
        this.limits = List.of(
                new Limit("register", post("/api/v1/auth/register"), properties.registerCapacity(),
                        properties.registerPeriod()),
                new Limit("forgot", post("/api/v1/auth/password/forgot"), properties.forgotCapacity(),
                        properties.forgotPeriod()),
                new Limit("reset", post("/api/v1/auth/password/reset"), properties.resetCapacity(),
                        properties.resetPeriod()));
        this.problems = Objects.requireNonNull(problems, "problems");
        this.timeMeter = new ClockTimeMeter(clock);
        this.buckets = Caffeine.newBuilder()
                .ticker(timeMeter::currentTimeNanos)
                .expireAfterAccess(longest(properties)) // an idle bucket is full again by then
                .maximumSize(MAX_BUCKETS)
                .build();
    }

    private static Duration longest(AuthRateLimitProperties p) {
        Duration max = p.registerPeriod();
        for (Duration d : List.of(p.forgotPeriod(), p.resetPeriod())) {
            if (d.compareTo(max) > 0) {
                max = d;
            }
        }
        return max;
    }

    private static RequestMatcher post(String path) {
        return PathPatternRequestMatcher.pathPattern(HttpMethod.POST, path);
    }

    private Limit limitOf(HttpServletRequest request) {
        for (Limit limit : limits) {
            if (limit.matcher().matches(request)) {
                return limit;
            }
        }
        return null;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return limitOf(request) == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Limit limit = limitOf(request);
        Bucket bucket = buckets.get(limit.name() + "|" + request.getRemoteAddr(), key -> newBucket(limit));
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (!probe.isConsumed()) {
            problems.respond(request, response, new RetryAfterException(ErrorCode.RATE_LIMITED,
                    Duration.ofNanos(probe.getNanosToWaitForRefill())));
            return;
        }
        chain.doFilter(request, response);
    }

    private Bucket newBucket(Limit limit) {
        return Bucket.builder()
                .addLimit(l -> l.capacity(limit.capacity()).refillGreedy(limit.capacity(), limit.period()))
                .withCustomTimePrecision(timeMeter)
                .build();
    }
}
