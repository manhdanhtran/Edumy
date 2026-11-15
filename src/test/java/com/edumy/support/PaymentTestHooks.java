package com.edumy.support;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import com.edumy.commerce.api.OrderPaid;
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

    @EventListener
    void on(OrderPaid event) {
        published.add(event);
        if (failNext.getAndSet(false)) {
            throw new IllegalStateException("injected failure after the enrollments were granted (NFR-04 probe)");
        }
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
        published.clear();
    }
}
