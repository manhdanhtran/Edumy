package com.edumy.shared.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns the timers on only when {@code edumy.scheduler.enabled=true} (NFR-09, P11-e): the production / dev default in
 * {@code application.yml}; {@code application-test.yml} sets it to {@code false}, so no job runs behind a test's back —
 * tests call the services directly. The scheduler beans carry the same condition. Uses Boot's auto-configured
 * {@code TaskScheduler}.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "edumy.scheduler.enabled", havingValue = "true")
class SchedulingConfig {
}
