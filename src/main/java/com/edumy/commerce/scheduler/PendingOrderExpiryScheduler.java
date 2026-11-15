package com.edumy.commerce.scheduler;

import com.edumy.commerce.service.CommerceExpiryService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * BR-29 / AC-22 timer (NFR-09): every {@code edumy.scheduler.interval} (5 minutes) the pending orders older than 24 h
 * are looked up and cancelled as {@code expired} (or recorded as paid). Safe with several instances ({@code SKIP LOCKED},
 * one order per transaction).
 */
@Component
@ConditionalOnProperty(name = "edumy.scheduler.enabled", havingValue = "true")
public class PendingOrderExpiryScheduler {

    private final CommerceExpiryService expiry;

    public PendingOrderExpiryScheduler(CommerceExpiryService expiry) {
        this.expiry = expiry;
    }

    @Scheduled(fixedDelayString = "${edumy.scheduler.interval:PT5M}",
            initialDelayString = "${edumy.scheduler.initial-delay:PT1M}")
    public void run() {
        expiry.cancelOverdueOrders();
    }
}
