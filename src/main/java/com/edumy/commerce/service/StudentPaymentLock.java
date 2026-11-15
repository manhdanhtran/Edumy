package com.edumy.commerce.service;

import java.time.Clock;

import com.edumy.commerce.repository.CartRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The per-student lock of ADR-011 (P11-fix-a, review P11-R-02 / R-04): the student's {@code carts} row, locked
 * {@code FOR UPDATE} <b>first</b> by every flow that may apply a payment result or grant enrollments — T2 (webhook and
 * the timers' "found paid" step), UC-S16 cancel, UC-S05 create QR, UC-A18, and T1 (which locks the cart entity itself).
 * Two such flows of one student are therefore serialised even when they touch two different orders, so "which courses
 * does the student own" (BR-30, Q3) is always read after the other flow committed.
 * <p>A student without a cart gets an empty one first ({@code INSERT … ON DUPLICATE KEY UPDATE}, idempotent on
 * {@code uq_carts_student}; BR-22 allows an empty cart). Lock order stays carts → orders → payment_transactions →
 * enrollments / cart_items.
 */
@Component
class StudentPaymentLock {

    private final CartRepository carts;
    private final Clock clock;

    StudentPaymentLock(CartRepository carts, Clock clock) {
        this.carts = carts;
        this.clock = clock;
    }

    /** Locks (creating it if needed) the cart row of {@code studentId} in the caller's transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    void lock(Long studentId) {
        carts.createIfAbsent(studentId, clock.instant());
        carts.lockIdByStudentId(studentId)
                .orElseThrow(() -> new IllegalStateException("cart row missing right after the upsert"));
    }
}
