package com.edumy.commerce.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import com.edumy.commerce.config.PaymentProperties;
import com.edumy.commerce.entity.CancelReason;
import com.edumy.commerce.entity.Order;
import com.edumy.commerce.integration.PaymentGatewayException;
import com.edumy.commerce.repository.OrderRepository;
import com.edumy.commerce.service.PendingPaymentSettler.Settlement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The two commerce timers of SRS §3.x "Bộ hẹn giờ" (NFR-09: every 5 minutes, safe with several instances, no external
 * lock library — ADR-011): close expired QR attempts after a lookup (UC-S05 A1) and cancel pending orders older than
 * 24 h after a lookup (UC-S16 by the timer, BR-29, AC-22).
 * <p>Each order is handled in its <b>own</b> transaction: claim one row {@code FOR UPDATE SKIP LOCKED} (another
 * instance, a student request or a webhook holding it → skipped, never waited for), settle its pending attempt through
 * {@link PendingPaymentSettler} (same rules as UC-S16), commit. A provider failure or any other error rolls that one
 * order back, is logged, and the run continues; the order is retried at the next run (UC-S16 E2). Ids handled in this
 * run are excluded from the next claims (sentinel {@code -1} keeps the list non-empty), so a failing order is not
 * retried in a loop. At most {@link #BATCH} orders per run.
 */
@Service
public class CommerceExpiryService {

    private static final Logger log = LoggerFactory.getLogger(CommerceExpiryService.class);
    static final int BATCH = 200;
    static final Long SENTINEL = -1L;

    /** What one run did. */
    public record RunResult(int processed, int failed) {
    }

    private enum Step { DONE, FAILED }

    private record Claimed(Long orderId, Step step) {
    }

    private final OrderRepository orders;
    private final PendingPaymentSettler settler;
    private final PaymentProperties properties;
    private final TransactionTemplate tx;
    private final Clock clock;

    public CommerceExpiryService(OrderRepository orders, PendingPaymentSettler settler, PaymentProperties properties,
                                 PlatformTransactionManager transactionManager, Clock clock) {
        this.orders = orders;
        this.settler = settler;
        this.properties = properties;
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setTimeout(properties.txTimeoutSeconds());
        this.clock = clock;
    }

    /**
     * UC-S05 A1: every pending attempt whose QR expired is looked up — not paid → {@code expired}, failed →
     * {@code failed}, paid → T2 (order paid, enrollments; or needs_review). The order itself stays as it is.
     */
    public RunResult expireQrAttempts() {
        Instant now = clock.instant();
        return run("QR expiry", seen -> orders.claimOrderWithExpiredQr(now, seen), order -> {
            Settlement settlement = settler.settle(order);
            log.info("QR expiry: orderCode={}, settlement={}", order.getOrderCode(), settlement);
        });
    }

    /**
     * BR-29 / AC-22 / UC-S16 (timer): every pending order created 24 h ago or earlier — its pending attempt is looked
     * up first; paid → the order is paid instead of cancelled; otherwise the order is cancelled as {@code expired}.
     */
    public RunResult cancelOverdueOrders() {
        Instant now = clock.instant();
        Instant cutoff = now.minus(properties.orderTtl());
        return run("Overdue orders", seen -> orders.claimOverdueOrder(cutoff, seen), order -> {
            Settlement settlement = settler.settle(order);
            if (settlement == Settlement.PAID) {
                log.info("Overdue order not cancelled, the lookup found it paid: orderCode={}", order.getOrderCode());
                return;
            }
            order.cancel(CancelReason.EXPIRED, clock.instant());
            log.info("Order cancelled as expired (24 h): orderCode={}, settlement={}", order.getOrderCode(),
                    settlement);
        });
    }

    private RunResult run(String job, Function<Collection<Long>, Optional<Long>> claim,
                          Consumer<Order> work) {
        Set<Long> seen = new LinkedHashSet<>();
        int processed = 0;
        int failed = 0;
        while (processed + failed < BATCH) {
            AtomicReference<Long> current = new AtomicReference<>();
            Claimed claimed;
            try {
                claimed = tx.execute(status -> claimAndWork(job, claim, work, seen, current, status));
            } catch (RuntimeException e) { // the commit itself failed: nothing of this order was kept
                if (current.get() == null) {
                    throw e;
                }
                log.error("{}: order not committed, retried next run: orderId={}, error={}", job, current.get(),
                        e.getClass().getName());
                claimed = new Claimed(current.get(), Step.FAILED);
            }
            if (claimed == null) {
                break;
            }
            seen.add(claimed.orderId());
            if (claimed.step() == Step.DONE) {
                processed++;
            } else {
                failed++;
            }
        }
        if (processed + failed > 0) {
            log.info("{} run: processed={}, failed={}", job, processed, failed);
        }
        return new RunResult(processed, failed);
    }

    /** One order in one transaction; {@code null} when nothing is left to claim. */
    private Claimed claimAndWork(String job, Function<Collection<Long>, Optional<Long>> claim, Consumer<Order> work,
                                 Set<Long> seen, AtomicReference<Long> current, TransactionStatus status) {
        Optional<Long> id = claim.apply(seenOrSentinel(seen));
        if (id.isEmpty()) {
            return null;
        }
        current.set(id.get());
        try {
            Order order = orders.findByIdForUpdate(id.get())
                    .orElseThrow(() -> new IllegalStateException("claimed order vanished"));
            work.accept(order);
            return new Claimed(id.get(), Step.DONE);
        } catch (PaymentGatewayException e) {
            status.setRollbackOnly();
            log.warn("{}: provider unavailable, retried next run: orderId={}, error={}", job, id.get(),
                    e.getMessage());
            return new Claimed(id.get(), Step.FAILED);
        } catch (RuntimeException e) {
            status.setRollbackOnly();
            log.error("{}: order not processed, retried next run: orderId={}, error={}", job, id.get(),
                    e.getClass().getName());
            return new Claimed(id.get(), Step.FAILED);
        }
    }

    /** The ids to exclude, never empty: an empty list would render {@code NOT IN ()} (invalid SQL). */
    static List<Long> seenOrSentinel(Collection<Long> seen) {
        List<Long> ids = new ArrayList<>(seen);
        if (ids.isEmpty()) {
            ids.add(SENTINEL);
        }
        return ids;
    }
}
