package com.edumy.identity.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;

import com.edumy.shared.error.ErrorCode;
import com.edumy.shared.error.RetryAfterException;
import com.edumy.support.ManualClock;
import org.junit.jupiter.api.Test;

/** NFR-03 semantics of {@link LoginAttemptLimiter} with a clock the test moves (it also drives Caffeine's ticker). */
class LoginAttemptLimiterTest {

    static final String EMAIL = "student@edumy.vn";
    static final Instant START = Instant.parse("2026-11-13T08:00:00Z");

    final ManualClock clock = new ManualClock(START);
    final LoginAttemptLimiter limiter = new LoginAttemptLimiter(clock);

    @Test
    void fourFailures_notBlocked_NFR03() {
        fail(4, Duration.ofSeconds(30));

        assertNotBlocked(EMAIL);
    }

    @Test
    void fifthFailure_startsBlockOf15MinFromThatFailure_NFR03() {
        fail(4, Duration.ofMinutes(1));
        clock.advance(Duration.ofMinutes(2));
        Instant fifth = clock.instant();
        limiter.recordFailure(EMAIL); // the 5th failure itself is a normal 401 for the caller

        assertBlocked(EMAIL, 900);
        clock.setInstant(fifth.plus(Duration.ofMinutes(15)).minusNanos(1));
        assertBlocked(EMAIL, 1);
    }

    @Test
    void attemptsDuringBlock_doNotExtendIt_blockEndsAt15MinPlus0s_NFR03() {
        fail(5, Duration.ZERO);
        Instant fifth = clock.instant();

        clock.setInstant(fifth.plus(Duration.ofMinutes(10)));
        for (int i = 0; i < 20; i++) {
            assertBlocked(EMAIL, 300);
        }
        // the blocked checks did not write: the entry still dates from failure #5
        assertThat(limiter.entryAge(EMAIL)).contains(Duration.ofMinutes(10));

        clock.setInstant(fifth.plus(Duration.ofMinutes(15)));
        assertNotBlocked(EMAIL);
    }

    @Test
    void failureRecordedByARacingRequestDuringBlock_doesNotMoveBlockedUntil() {
        fail(5, Duration.ZERO);
        Instant fifth = clock.instant();

        clock.setInstant(fifth.plus(Duration.ofMinutes(10)));
        limiter.recordFailure(EMAIL); // a request that passed the check just before the block started

        assertBlocked(EMAIL, 300);
        clock.setInstant(fifth.plus(Duration.ofMinutes(15)));
        assertNotBlocked(EMAIL);
    }

    @Test
    void success_resetsCounter_NFR03() {
        fail(4, Duration.ofSeconds(10));
        limiter.reset(EMAIL);
        fail(4, Duration.ofSeconds(10));

        assertNotBlocked(EMAIL);
        limiter.recordFailure(EMAIL);
        assertBlocked(EMAIL, 900);
    }

    @Test
    void failuresSpaced15MinApart_doNotAccumulate() {
        for (int i = 0; i < 10; i++) {
            limiter.recordFailure(EMAIL);
            clock.advance(Duration.ofMinutes(15));
        }

        assertNotBlocked(EMAIL);
    }

    @Test
    void failuresLessThan15MinApart_accumulate_eachFailureRefreshesTheWindow() {
        for (int i = 0; i < 4; i++) {
            limiter.recordFailure(EMAIL);
            clock.advance(Duration.ofMinutes(14).plusSeconds(59));
        }
        limiter.recordFailure(EMAIL);

        assertBlocked(EMAIL, 900);
    }

    @Test
    void neverPermanent_afterBlockTheCountStartsAgain_NFR03() {
        for (int round = 0; round < 3; round++) {
            fail(5, Duration.ofSeconds(1));
            assertBlocked(EMAIL, 900);
            clock.advance(Duration.ofMinutes(15));
            assertNotBlocked(EMAIL);

            limiter.recordFailure(EMAIL); // first failure after the block: counts as 1, no new block
            assertNotBlocked(EMAIL);
            limiter.reset(EMAIL);
        }
    }

    @Test
    void blockEnded_thenFailureWhileOldEntryStillCached_countsFromOne() {
        fail(5, Duration.ZERO);
        clock.advance(Duration.ofMinutes(15)); // block over, entry expired (expireAfterWrite 15 min)

        fail(4, Duration.ofSeconds(1));
        assertNotBlocked(EMAIL);
        limiter.recordFailure(EMAIL);
        assertBlocked(EMAIL, 900);
    }

    @Test
    void emailsAreIndependent_unknownEmailsCountedToo() {
        fail(5, Duration.ZERO);

        assertBlocked(EMAIL, 900);
        assertNotBlocked("other@edumy.vn");
    }

    @Test
    void retryAfter_roundsUpToWholeSeconds() {
        fail(5, Duration.ZERO);
        clock.advance(Duration.ofMinutes(10).plusMillis(500)); // 4 min 59.5 s left

        assertBlocked(EMAIL, 300);
    }

    // ------------------------------------------------------------------------------------------------------ helpers

    private void fail(int times, Duration between) {
        for (int i = 0; i < times; i++) {
            if (i > 0) {
                clock.advance(between);
            }
            assertNotBlocked(EMAIL); // the login flow always checks first
            limiter.recordFailure(EMAIL);
        }
    }

    private void assertNotBlocked(String email) {
        assertThatCode(() -> limiter.checkNotBlocked(email)).doesNotThrowAnyException();
    }

    private void assertBlocked(String email, long retryAfterSeconds) {
        assertThatThrownBy(() -> limiter.checkNotBlocked(email))
                .isInstanceOfSatisfying(RetryAfterException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(ErrorCode.AUTH_LOGIN_TEMPORARILY_BLOCKED);
                    assertThat(ex.retryAfterSeconds()).isEqualTo(retryAfterSeconds);
                });
    }
}
