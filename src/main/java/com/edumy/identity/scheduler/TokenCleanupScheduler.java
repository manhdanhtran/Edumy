package com.edumy.identity.scheduler;

import com.edumy.identity.service.TokenCleanupService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Every {@code edumy.scheduler.interval} (5 minutes, NFR-09): codes expired more than 30 days ago are deleted. */
@Component
@ConditionalOnProperty(name = "edumy.scheduler.enabled", havingValue = "true")
public class TokenCleanupScheduler {

    private final TokenCleanupService cleanup;

    public TokenCleanupScheduler(TokenCleanupService cleanup) {
        this.cleanup = cleanup;
    }

    @Scheduled(fixedDelayString = "${edumy.scheduler.interval:PT5M}",
            initialDelayString = "${edumy.scheduler.initial-delay:PT1M}")
    public void run() {
        cleanup.deleteExpiredCodes();
    }
}
