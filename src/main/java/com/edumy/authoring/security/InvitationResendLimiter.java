package com.edumy.authoring.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

import com.edumy.shared.error.ErrorCode;
import com.edumy.shared.error.RetryAfterException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

/**
 * UC-I13 E4 "cho gửi lại thư" (P10): at most one invitation mail per invitation every {@value #COOLDOWN_SECONDS}
 * seconds, counted from the later of the invitation time ({@code invited_at}, the first mail) and the last resend. A
 * resend inside the window → 429 {@code INVITATION_RESEND_TOO_SOON} + {@code Retry-After}. Nothing is stored in the
 * database (the invitation's state and time stay unchanged): the last resend lives in a bounded Caffeine cache whose
 * ticker is the application {@link Clock}. Same known limitation as the other in-memory limiters (ADR-002: single
 * instance, reset on restart — at worst one extra mail).
 */
@Component
public class InvitationResendLimiter {

    static final long COOLDOWN_SECONDS = 60;
    static final Duration COOLDOWN = Duration.ofSeconds(COOLDOWN_SECONDS);
    static final long MAX_ENTRIES = 100_000;

    private final Clock clock;
    private final Cache<Long, Instant> lastResend;

    public InvitationResendLimiter(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.lastResend = Caffeine.newBuilder()
                .ticker(() -> ChronoUnit.NANOS.between(Instant.EPOCH, clock.instant()))
                .expireAfterWrite(COOLDOWN)
                .maximumSize(MAX_ENTRIES)
                .build();
    }

    /**
     * Records a resend of {@code invitationId} now, or throws 429 when the previous mail (invitation or resend) is less
     * than 60 s old. Atomic per invitation: of two concurrent resends exactly one passes.
     */
    public void acquire(Long invitationId, Instant invitedAt) {
        Objects.requireNonNull(invitationId, "invitationId");
        Objects.requireNonNull(invitedAt, "invitedAt");
        Instant now = clock.instant();
        Instant[] blockedUntil = new Instant[1];
        lastResend.asMap().compute(invitationId, (id, last) -> {
            Instant previous = last == null || last.isBefore(invitedAt) ? invitedAt : last;
            Instant until = previous.plus(COOLDOWN);
            if (now.isBefore(until)) {
                blockedUntil[0] = until;
                return last;
            }
            return now;
        });
        if (blockedUntil[0] != null) {
            throw new RetryAfterException(ErrorCode.INVITATION_RESEND_TOO_SOON, Duration.between(now, blockedUntil[0]));
        }
    }
}
