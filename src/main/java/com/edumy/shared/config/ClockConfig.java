package com.edumy.shared.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * UTC {@link Clock} for every time-dependent rule (docs/ai/07): services and entities take {@code Instant.now(clock)},
 * never {@code Instant.now()}, so tests can use a fixed clock.
 */
@Configuration(proxyBeanMethods = false)
class ClockConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
