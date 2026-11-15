package com.edumy.identity.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import com.edumy.identity.repository.UserTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Physical deletion of email-verification / password-reset codes that expired more than 30 days ago (SRS §6, the only
 * identity rows that may be deleted; BR-28 keeps everything else). Batches of {@link #BATCH} rows, each in its own short
 * transaction, so the job never holds many row locks; safe with several instances (a row is deleted once).
 */
@Service
public class TokenCleanupService {

    private static final Logger log = LoggerFactory.getLogger(TokenCleanupService.class);
    static final Duration RETENTION = Duration.ofDays(30);
    static final int BATCH = 1000;

    private final UserTokenRepository tokens;
    private final TransactionTemplate tx;
    private final Clock clock;

    public TokenCleanupService(UserTokenRepository tokens, PlatformTransactionManager transactionManager, Clock clock) {
        this.tokens = tokens;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** Returns the number of codes deleted. */
    public int deleteExpiredCodes() {
        Instant cutoff = clock.instant().minus(RETENTION);
        int total = 0;
        int deleted;
        do {
            Integer batch = tx.execute(status -> tokens.deleteExpiredBefore(cutoff, BATCH));
            deleted = batch == null ? 0 : batch;
            total += deleted;
        } while (deleted == BATCH);
        if (total > 0) {
            log.info("Expired account codes deleted: count={}", total);
        }
        return total;
    }
}
