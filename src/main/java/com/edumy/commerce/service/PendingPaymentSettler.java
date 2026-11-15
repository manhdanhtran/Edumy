package com.edumy.commerce.service;

import java.time.Clock;
import java.util.Optional;

import com.edumy.commerce.entity.Order;
import com.edumy.commerce.entity.PaymentTransaction;
import com.edumy.commerce.integration.PaymentGateway;
import com.edumy.commerce.integration.PaymentGatewayException;
import com.edumy.commerce.integration.PaymentLookup;
import com.edumy.commerce.integration.PaymentResult;
import com.edumy.commerce.repository.PaymentTransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Look the pending attempt up before closing it" (UC-S04 step 2, UC-S16 steps 1–2 / E1, UC-S05 A1, BR-16, BR-29):
 * the pending attempt of a locked order is locked ({@code FOR UPDATE}, after the order — ADR-011 lock order), the
 * provider is asked, and the answer is applied: paid → T2 ({@link PaymentResultApplier}: paid, or needs_review on a
 * mismatch / conflict), failed → {@code failed}, not paid → {@code expired}. The order itself is left to the caller
 * (cancel, replace, keep). A provider failure ({@link PaymentGatewayException}) propagates: the caller's transaction
 * rolls back and nothing changes (UC-S16 E2).
 */
@Component
class PendingPaymentSettler {

    private static final Logger log = LoggerFactory.getLogger(PendingPaymentSettler.class);

    /** What the lookup did to the order's pending attempt. */
    enum Settlement {
        /** No pending attempt: nothing to look up. */
        NONE,
        /** The attempt was closed (expired or failed): the order may be cancelled / replaced / paid again. */
        CLOSED,
        /** The order is paid now (T2 applied, enrollments granted): the caller stops. */
        PAID,
        /** Money arrived but cannot be applied (mismatch / conflict): attempt in the UC-A18 queue, order untouched. */
        NEEDS_REVIEW
    }

    private final PaymentTransactionRepository payments;
    private final PaymentGateway gateway;
    private final PaymentResultApplier applier;
    private final Clock clock;

    PendingPaymentSettler(PaymentTransactionRepository payments, PaymentGateway gateway, PaymentResultApplier applier,
                          Clock clock) {
        this.payments = payments;
        this.gateway = gateway;
        this.applier = applier;
        this.clock = clock;
    }

    /**
     * Request flows (T1, UC-S16, UC-S05): {@code order} locked by the caller's transaction, which also holds the
     * student's cart lock ({@link StudentPaymentLock}), so a "paid" answer is applied here (T2).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    Settlement settle(Order order) {
        Lookup lookup = lookUp(order);
        if (lookup.paid() == null) {
            return lookup.settlement();
        }
        return switch (applier.apply(order, lookup.payment(), lookup.paid())) {
            case PAID -> Settlement.PAID;
            case NEEDS_REVIEW -> Settlement.NEEDS_REVIEW;
            case FAILED, IGNORED -> Settlement.CLOSED;
        };
    }

    /**
     * Timers (P11-fix-a): the claim transaction holds only the order row, not the cart lock, so it must not grant
     * anything. Not paid → {@code expired}, failed → {@code failed} (no grant, safe here); paid → nothing changed, the
     * result is returned and the caller applies it afterwards in a new transaction that takes the locks in the standard
     * order (carts → orders → attempt). {@code null} when there was nothing to apply.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    PaymentResult settleUnlessPaid(Order order) {
        Lookup lookup = lookUp(order);
        return lookup.paid();
    }

    /** What one lookup found: the settlement, and the attempt + result when the provider reports it paid. */
    private record Lookup(Settlement settlement, PaymentTransaction payment, PaymentResult paid) {
    }

    private Lookup lookUp(Order order) {
        Optional<PaymentTransaction> pending = payments.findPendingByOrderIdForUpdate(order.getId());
        if (pending.isEmpty()) {
            return new Lookup(Settlement.NONE, null, null);
        }
        PaymentTransaction payment = pending.get();
        PaymentLookup answer = gateway.query(payment.getTransactionRef());
        log.info("Payment lookup: transactionRef={}, orderCode={}, answer={}", payment.getTransactionRef(),
                order.getOrderCode(), answer.getClass().getSimpleName());
        PaymentResult result = PaymentResult.fromLookup(payment.getTransactionRef(), answer);
        if (result == null) {
            payment.expire(clock.instant());
            return new Lookup(Settlement.CLOSED, payment, null);
        }
        if (!result.success()) {
            applier.apply(order, payment, result); // pending → failed, grants nothing
            return new Lookup(Settlement.CLOSED, payment, null);
        }
        return new Lookup(Settlement.PAID, payment, result);
    }
}