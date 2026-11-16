package com.edumy.identity.scheduler;

import com.edumy.identity.service.TokenCleanupService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Once a day (ADR-012; {@code edumy.scheduler.token-cleanup-cron}, default 03:00 in {@code edumy.scheduler.zone}): codes
 * expired more than 30 days ago are deleted (SRS §6). Not on the 5-minute cycle: nothing waits for it.
 */
@Component
@ConditionalOnProperty(name = "edumy.scheduler.enabled", havingValue = "true")
public class TokenCleanupScheduler {

    private final TokenCleanupService cleanup;

    public TokenCleanupScheduler(TokenCleanupService cleanup) {
        this.cleanup = cleanup;
    }

    @Scheduled(cron = "${edumy.scheduler.token-cleanup-cron:0 0 3 * * *}",
            zone = "${edumy.scheduler.zone:Asia/Ho_Chi_Minh}")
    public void run() {
        cleanup.deleteExpiredCodes();
    }
}
