package com.edumy.shared.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Enables {@code @Async}, required by {@code @ApplicationModuleListener} (async, after commit) for emails and other
 * side effects that may fail independently (BR-20, ADR-006). Uses Boot's auto-configured task executor.
 */
@Configuration(proxyBeanMethods = false)
@EnableAsync
class AsyncConfig {
}
