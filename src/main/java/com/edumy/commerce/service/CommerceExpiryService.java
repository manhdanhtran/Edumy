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
import java.util.function.Function;

import com.edumy.commerce.config.PaymentProperties;
import com.edumy.commerce.entity.CancelReason;
import com.edumy.commerce.entity.Order;
import com.edumy.commerce.integration.PaymentGatewayException;
import com.edumy.commerce.integration.PaymentResult;
import com.edumy.commerce.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The two commerce timers (NFR-09: every 5 minutes, safe with several instances, no external lock library — ADR-011):
 * close expired QR attempts after a lookup (UC-S05 A1) and cancel pending orders older than 24 h after a lookup
 * (UC-S16 by the timer, BR-29, AC-22).
 * <p>Each order is handled in its <b>own</b> claim transaction: one row {@code FOR UPDATE SKIP LOCKED} (held elsewhere →
 * skipped, never waited for), then the lookup of its pending attempt ({@link PendingPaymentSettler#settleUnlessPaid}):
 * not paid → {@code expired}, failed → {@code failed}, and for the 24 h job the order is cancelled — all inside the claim
 * transaction, which grants nothing and so needs no cart lock. <b>Paid</b> → the claim transaction ends without a change
 * and T2 runs in a new transaction with the standard lock order (buyer's cart → order → attempt,
 * {@link PaymentService#applyProviderResult}, P11-fix-a); T2 is idempotent, so a webhook or another instance applying
 * the same result meanwhile is harmless.
 * <p>A provider failure or any other error rolls back that one order (logged with its stack trace), the run continues,
 * and the order is retried at the next run (UC-S16 E2). Ids handled in this run are excluded from the next claims
 * (sentinel {@code -1} keeps the list non-empty). At most {@link #BATCH} orders per run.
 */
@Service
public class CommerceExpiryService {

    private static final Logger log = LoggerFactory.getLogger(CommerceExpiryService.class);
    static final int BATCH = 200;
    static final Long SENTINEL = -1L;

    /** What one run did. */
    public record RunResult(int processed, int failed) {
    }

    /** One claimed order: done or failed, and the paid result to apply after the claim transaction. */
    private record Claimed(Long orderId, boolean done, PaymentResult paid) {
    }

    private final OrderRepository orders;
    private final PendingPaymentSettler settler;
    private final PaymentService payments;
    private final PaymentProperties properties;
    private final TransactionTemplate tx;
    private final Clock clock;

    public CommerceExpiryService(OrderRepository orders, PendingPaymentSettler settler, PaymentService payments,
                                 PaymentProperties properties, PlatformTransactionManager transactionManager,
                                 Clock clock) {
        this.orders = orders;
        this.settler = settler;
        this.payments = payments;
        this.properties = properties;
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setTimeout(properties.txTimeoutSeconds());
        this.clock = clock;
    }

    /**
     * UC-S05 A1: every pending attempt whose QR expired is looked up — not paid → {@code expired}, failed →
     * {@code failed}, paid → T2 afterwards (order paid + enrollments, or needs_review).
     */
    public RunResult expireQrAttempts() {
        Instant now = clock.instant();
        return run("QR expiry", seen -> orders.claimOrderWithExpiredQr(now, seen), settler::settleUnlessPaid);
    }

    /**
     * BR-29 / AC-22 / UC-S16 (timer): every pending order created 24 h ago or earlier — its pending attempt is looked
     * up first; paid → T2 afterwards (the order becomes paid, not cancelled); otherwise cancelled as {@code expired}.
     */
    public RunResult cancelOverdueOrders() {
        Instant cutoff = clock.instant().minus(properties.orderTtl());
        return run("Overdue orders", seen -> orders.claimOverdueOrder(cutoff, seen), order -> {
            PaymentResult paid = settler.settleUnlessPaid(order);
            if (paid == null) {
                order.cancel(CancelReason.EXPIRED, clock.instant());
                log.info("Order cancelled as expired (24 h): orderCode={}", order.getOrderCode());
            }
            return paid;
        });
    }

    private RunResult run(String job, Function<Collection<Long>, Optional<Long>> claim,
                          Function<Order, PaymentResult> work) {
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
                log.error("{}: order not committed, retried next run: orderId={}", job, current.get(), e);
                claimed = new Claimed(current.get(), false, null);
            }
            if (claimed == null) {
                break;
            }
            seen.add(claimed.orderId());
            boolean done = claimed.done() && applyPaid(job, claimed);
            if (done) {
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

    /** One order in one claim transaction; {@code null} when nothing is left to claim. */
    private Claimed claimAndWork(String job, Function<Collection<Long>, Optional<Long>> claim,
                                 Function<Order, PaymentResult> work, Set<Long> seen, AtomicReference<Long> current,
                                 TransactionStatus status) {
        Optional<Long> id = claim.apply(seenOrSentinel(seen));
        if (id.isEmpty()) {
            return null;
        }
        current.set(id.get());
        try {
            Order order = orders.findByIdForUpdate(id.get())
                    .orElseThrow(() -> new IllegalStateException("claimed order vanished"));
            return new Claimed(id.get(), true, work.apply(order));
        } catch (PaymentGatewayException e) {
            status.setRollbackOnly();
            log.warn("{}: provider unavailable, retried next run: orderId={}, error={}", job, id.get(),
                    PaymentGatewayException.describe(e));
            return new Claimed(id.get(), false, null);
        } catch (RuntimeException e) {
            status.setRollbackOnly();
            log.error("{}: order not processed, retried next run: orderId={}", job, id.get(), e);
            return new Claimed(id.get(), false, null);
        }
    }

    /** "Found paid": T2 in a new transaction, standard lock order (P11-fix-a). */
    private boolean applyPaid(String job, Claimed claimed) {
        if (claimed.paid() == null) {
            return true;
        }
        try {
            payments.applyProviderResult(claimed.paid());
            return true;
        } catch (RuntimeException e) {
            log.error("{}: paid result not applied, retried next run: orderId={}", job, claimed.orderId(), e);
            return false;
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
