package com.edumy.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;

import org.springframework.context.annotation.Primary;

/**
 * The application {@link Clock} of {@link MySqlSpringBootTest} tests (registered as the {@code @Primary} Clock bean,
 * so services, entities, {@code JwtIssuer} and the JWT timestamp validator all read it). It follows the real UTC time
 * plus an offset that a test can move: {@link #advance} (e.g. past a 24 h code expiry) or {@link #setInstant}. Time
 * keeps running from there. {@link MutableClockResetExtension} puts it back to the real time after every test, so a
 * shifted clock never leaks into the next test of the shared context.
 * <p>Only application time moves: MySQL {@code CURRENT_TIMESTAMP} and Caffeine expiry (own ticker) do not.
 */
@Primary
public class MutableClock extends Clock {

    private final Clock base = Clock.systemUTC();
    private volatile Duration offset = Duration.ZERO;

    /** Moves the clock forward ({@code amount} may be negative to move back). */
    public synchronized void advance(Duration amount) {
        offset = offset.plus(Objects.requireNonNull(amount, "amount"));
    }

    /** The clock now reads {@code instant} and keeps running from there. */
    public synchronized void setInstant(Instant instant) {
        offset = Duration.between(base.instant(), Objects.requireNonNull(instant, "instant"));
    }

    /** Back to the real time. */
    public synchronized void reset() {
        offset = Duration.ZERO;
    }

    @Override
    public Instant instant() {
        return base.instant().plus(offset);
    }

    @Override
    public long millis() {
        return instant().toEpochMilli();
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    /** A view in another zone that still follows this clock's offset. */
    @Override
    public Clock withZone(ZoneId zone) {
        MutableClock owner = this;
        return new Clock() {
            @Override
            public ZoneId getZone() {
                return zone;
            }

            @Override
            public Clock withZone(ZoneId other) {
                return owner.withZone(other);
            }

            @Override
            public Instant instant() {
                return owner.instant();
            }
        };
    }

    @Override
    public String toString() {
        return "MutableClock[offset=" + offset + "]";
    }
}
