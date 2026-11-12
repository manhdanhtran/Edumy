package com.edumy.shared.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Typed {@code edumy.*} configuration (application.yml). */
@ConfigurationProperties("edumy")
public record EdumyProperties(
        @DefaultValue Security security,
        @DefaultValue Cors cors,
        @DefaultValue Scheduler scheduler,
        @DefaultValue Payment payment) {

    public record Security(@DefaultValue Jwt jwt) {
    }

    /** docs/ai/04 §4.1: access 15 min, refresh 14 days, HS256 secret (base64, ≥ 32 bytes) from EDUMY_JWT_SECRET. */
    public record Jwt(
            @DefaultValue("15m") Duration accessTtl,
            @DefaultValue("14d") Duration refreshTtl,
            String secret) {

        @Override
        public String toString() {
            return "Jwt[accessTtl=" + accessTtl + ", refreshTtl=" + refreshTtl + ", secret=****]"; // NFR-08
        }
    }

    public record Cors(@DefaultValue List<String> allowedOrigins) {
    }

    /** Phase 1: every scheduler is disabled (ADR-005). */
    public record Scheduler(@DefaultValue("false") boolean enabled) {
    }

    public record Payment(@DefaultValue("fake") String provider) {
    }
}
