package com.edumy.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * A {@link Clock} for unit tests that stands still until the test moves it ({@link #advance}, {@link #setInstant}),
 * so exact boundaries (e.g. "at 15 min + 0 s") can be tested. Application-context tests use {@link MutableClock}.
 */
public class ManualClock extends Clock {

    private volatile Instant now;

    public ManualClock(Instant start) {
        this.now = Objects.requireNonNull(start, "start");
    }

    public synchronized void advance(Duration amount) {
        now = now.plus(Objects.requireNonNull(amount, "amount"));
    }

    public void setInstant(Instant instant) {
        now = Objects.requireNonNull(instant, "instant");
    }

    @Override
    public Instant instant() {
        return now;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return Clock.fixed(now, zone);
    }
}
