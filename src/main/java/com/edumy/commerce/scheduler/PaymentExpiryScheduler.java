package com.edumy.commerce.scheduler;

import com.edumy.commerce.service.CommerceExpiryService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * UC-S05 A1 timer (NFR-09): every {@code edumy.scheduler.interval} (5 minutes) the expired QR attempts are looked up
 * and closed. Safe with several instances: the service claims one order at a time with {@code SKIP LOCKED}.
 */
@Component
@ConditionalOnProperty(name = "edumy.scheduler.enabled", havingValue = "true")
public class PaymentExpiryScheduler {

    private final CommerceExpiryService expiry;

    public PaymentExpiryScheduler(CommerceExpiryService expiry) {
        this.expiry = expiry;
    }

    @Scheduled(fixedDelayString = "${edumy.scheduler.interval:PT5M}",
            initialDelayString = "${edumy.scheduler.initial-delay:PT1M}")
    public void run() {
        expiry.expireQrAttempts();
    }
}
