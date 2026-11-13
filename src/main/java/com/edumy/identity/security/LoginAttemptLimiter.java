package com.edumy.identity.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;

import com.edumy.shared.error.ErrorCode;
import com.edumy.shared.error.RetryAfterException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * NFR-03 login brute-force guard (docs/ai/04 §4.4), keyed by the normalized email, unknown emails included (so the
 * limiter does not reveal which emails exist).
 * <ul>
 *   <li>Failures 1–4 are recorded and the login answers the normal 401. The 5th consecutive failure also answers 401
 *       and starts a block until <i>time of failure #5 + 15 min</i>.</li>
 *   <li>While blocked, {@link #checkNotBlocked} rejects every attempt for that email (the right password too) with 429
 *       {@code AUTH_LOGIN_TEMPORARILY_BLOCKED} + {@code Retry-After}, before any user lookup or BCrypt. It only
 *       reads: a blocked attempt never extends the block nor refreshes the entry's expiry.</li>
 *   <li>Never permanent: at {@code blockedUntil} and later the block is gone. {@code blockedUntil} is compared with the
 *       {@link Clock} explicitly; cache expiry alone is not trusted for that.</li>
 *   <li>A successful password check ({@link #reset}) removes the entry.</li>
 * </ul>
 * <b>"Consecutive within 15 min"</b> = Caffeine {@code expireAfterWrite(15 min)}: each failure rewrites the entry and so
 * restarts its 15-minute life. The count survives as long as no gap between two failures reaches 15 min; a gap of
 * 15 min or more starts again at 1.
 * <p>Caffeine's ticker reads the injected {@link Clock}, so a test clock moves expiry as well. The cache is bounded
 * ({@value #MAX_ENTRIES} keys) against memory exhaustion by random emails; size eviction can only forget failures,
 * never create a block.
 * <p>Known limitation (ADR-002 "Abuse protection"): in-memory, single instance only, lost on restart. The email is
 * never logged (NFR-08 spirit: no personal data for a security event that anyone can trigger).
 */
@Component
public class LoginAttemptLimiter {

    static final int MAX_FAILURES = 5;
    static final Duration FAILURE_WINDOW = Duration.ofMinutes(15);
    static final Duration BLOCK_DURATION = Duration.ofMinutes(15);
    static final long MAX_ENTRIES = 100_000;

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptLimiter.class);

    private final Clock clock;
    private final Cache<String, Attempts> attempts;

    public LoginAttemptLimiter(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.attempts = Caffeine.newBuilder()
                .ticker(() -> ChronoUnit.NANOS.between(Instant.EPOCH, clock.instant()))
                .expireAfterWrite(FAILURE_WINDOW)
                .maximumSize(MAX_ENTRIES)
                .build();
    }

    /**
     * Rejects the attempt with 429 {@code AUTH_LOGIN_TEMPORARILY_BLOCKED} (Retry-After = seconds until the block ends,
     * rounded up) while {@code normalizedEmail} is blocked. Read-only: never writes the cache.
     */
    public void checkNotBlocked(String normalizedEmail) {
        Attempts current = attempts.getIfPresent(Objects.requireNonNull(normalizedEmail, "normalizedEmail"));
        if (current == null) {
            return;
        }
        Instant now = clock.instant();
        if (current.isBlockedAt(now)) {
            throw new RetryAfterException(ErrorCode.AUTH_LOGIN_TEMPORARILY_BLOCKED,
                    Duration.between(now, current.blockedUntil()));
        }
    }

    /** Records a failed password check (wrong password or unknown email); the 5th consecutive one starts the block. */
    public void recordFailure(String normalizedEmail) {
        Objects.requireNonNull(normalizedEmail, "normalizedEmail");
        Instant now = clock.instant();
        Attempts updated = attempts.asMap().compute(normalizedEmail, (key, current) -> next(current, now));
        if (updated != null && updated.consecutiveFailures() == MAX_FAILURES) {
            log.warn("Login temporarily blocked for {} after {} consecutive failures (email not logged)",
                    BLOCK_DURATION, MAX_FAILURES);
        }
    }

    /** Successful password check: forget the failures of {@code normalizedEmail}. */
    public void reset(String normalizedEmail) {
        attempts.invalidate(Objects.requireNonNull(normalizedEmail, "normalizedEmail"));
    }

    /** Time since the entry of {@code normalizedEmail} was last written, if present (for tests). */
    Optional<Duration> entryAge(String normalizedEmail) {
        return attempts.policy().expireAfterWrite().flatMap(policy -> policy.ageOf(normalizedEmail));
    }

    private static Attempts next(Attempts current, Instant now) {
        if (current == null || current.blockEndedAt(now)) {
            return new Attempts(1, null);
        }
        if (current.isBlockedAt(now)) {
            // only reachable by a request that passed checkNotBlocked just before another one started the block:
            // keep the block exactly as it is (blockedUntil is never moved)
            return current;
        }
        int failures = current.consecutiveFailures() + 1;
        return new Attempts(failures, failures >= MAX_FAILURES ? now.plus(BLOCK_DURATION) : null);
    }

    /** Immutable cache value; {@code blockedUntil} is null until failure #{@value #MAX_FAILURES}. */
    record Attempts(int consecutiveFailures, Instant blockedUntil) {

        boolean isBlockedAt(Instant now) {
            return blockedUntil != null && now.isBefore(blockedUntil);
        }

        boolean blockEndedAt(Instant now) {
            return blockedUntil != null && !now.isBefore(blockedUntil);
        }
    }
}
