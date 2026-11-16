package com.edumy.identity.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code edumy.security.auth-rate-limit.*} (P13, ADR-002): per-IP limits of the anonymous account endpoints. Defaults:
 * register 10 per hour, forgot-password 5 per 15 minutes, reset-password 10 per 15 minutes. The test profile raises
 * them (one IP runs the whole suite); the limiter itself is tested with explicit values.
 */
@ConfigurationProperties("edumy.security.auth-rate-limit")
public record AuthRateLimitProperties(
        @DefaultValue("10") long registerCapacity,
        @DefaultValue("1h") Duration registerPeriod,
        @DefaultValue("5") long forgotCapacity,
        @DefaultValue("15m") Duration forgotPeriod,
        @DefaultValue("10") long resetCapacity,
        @DefaultValue("15m") Duration resetPeriod) {

    public static AuthRateLimitProperties defaults() {
        return new AuthRateLimitProperties(10, Duration.ofHours(1), 5, Duration.ofMinutes(15), 10,
                Duration.ofMinutes(15));
    }
}