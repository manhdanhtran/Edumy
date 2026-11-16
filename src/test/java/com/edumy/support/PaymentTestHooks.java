package com.edumy.support;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import com.edumy.commerce.api.OrderPaid;
import com.edumy.commerce.api.PaymentReconciled;
import com.edumy.commerce.integration.FakeQrPaymentGateway;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.event.EventListener;

/**
 * Part of {@link MySqlSpringBootTest} (P11): a synchronous, in-transaction listener of {@link OrderPaid}. It records
 * every event published by T2 (so a test can count "paid" effects) and, when {@link #failNextOrderPaid armed}, throws
 * inside the T2 transaction AFTER the enrollments were granted and the cart lines removed — the NFR-04 "process killed
 * half way" probe: everything of that transaction must roll back. Reset after every test by
 * {@link PaymentTestHooksResetExtension} (with the fake payment gateway).
 */
@TestComponent
public class PaymentTestHooks {

    private final AtomicBoolean failNext = new AtomicBoolean();
    private final CopyOnWriteArrayList<OrderPaid> published = new CopyOnWriteArrayList<>();
    private final java.util.concurrent.atomic.AtomicReference<FakeQrPaymentGateway.Hold> hold =
            new java.util.concurrent.atomic.AtomicReference<>();
    private final java.util.concurrent.atomic.AtomicReference<FakeQrPaymentGateway.Hold> reconciledHold =
            new java.util.concurrent.atomic.AtomicReference<>();

    /** UC-A18 stops at its {@link PaymentReconciled} (end of the transaction, locks held) when armed. */
    @EventListener
    void on(PaymentReconciled event) {
        FakeQrPaymentGateway.Hold.pass(reconciledHold.getAndSet(null));
    }

    /** The next UC-A18 decision stops at its {@link PaymentReconciled} until the hold is released. */
    public FakeQrPaymentGateway.Hold holdNextReconciliation() {
        FakeQrPaymentGateway.Hold next = new FakeQrPaymentGateway.Hold();
        reconciledHold.set(next);
        return next;
    }

    @EventListener
    void on(OrderPaid event) {
        published.add(event);
        FakeQrPaymentGateway.Hold.pass(hold.getAndSet(null)); // T2 stops here holding cart, order and attempt locks
        if (failNext.getAndSet(false)) {
            throw new IllegalStateException("injected failure after the enrollments were granted (NFR-04 probe)");
        }
    }

    /**
     * The next T2 that pays an order stops at its {@link OrderPaid} (end of the transaction, every lock still held) until
     * the hold is released (review P11-R-03: real interleavings of application flows).
     */
    public FakeQrPaymentGateway.Hold holdNextOrderPaid() {
        FakeQrPaymentGateway.Hold next = new FakeQrPaymentGateway.Hold();
        hold.set(next);
        return next;
    }

    /** The next {@link OrderPaid} throws inside the publishing transaction. */
    public void failNextOrderPaid() {
        failNext.set(true);
    }

    /** {@link OrderPaid} events published (also those of rolled-back transactions). */
    public java.util.List<OrderPaid> published() {
        return java.util.List.copyOf(published);
    }

    public void reset() {
        failNext.set(false);
        hold.set(null);
        reconciledHold.set(null);
        published.clear();
    }
}
