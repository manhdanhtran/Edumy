package com.edumy.identity.security;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicLong;

import io.github.bucket4j.TimeMeter;

/**
 * Bucket4j time from the application {@link Clock} (nanoseconds since the epoch), made non-decreasing: a wall-clock
 * step back (NTP, or the test clock reset) would make Bucket4j subtract tokens for the negative interval, so the meter
 * holds its highest reading instead and refill resumes once the clock passes it again. Shared by the IP rate limiters.
 */
final class ClockTimeMeter implements TimeMeter {

    private final Clock clock;
    private final AtomicLong highest = new AtomicLong(Long.MIN_VALUE);

    ClockTimeMeter(Clock clock) {
        this.clock = clock;
    }

    @Override
    public long currentTimeNanos() {
        return highest.accumulateAndGet(ChronoUnit.NANOS.between(Instant.EPOCH, clock.instant()), Math::max);
    }

    @Override
    public boolean isWallClockBased() {
        return true;
    }
}