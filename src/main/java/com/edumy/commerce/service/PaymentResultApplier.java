package com.edumy.commerce.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import com.edumy.commerce.api.OrderPaid;
import com.edumy.commerce.entity.Order;
import com.edumy.commerce.entity.PaymentStatus;
import com.edumy.commerce.entity.PaymentTransaction;
import com.edumy.commerce.integration.PaymentResult;
import com.edumy.commerce.repository.CartItemRepository;
import com.edumy.commerce.repository.CartRepository;
import com.edumy.commerce.repository.PaymentTransactionRepository;

import com.edumy.learning.api.EnrollmentApi;
import com.edumy.learning.api.GrantEnrollmentCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * T2 "apply a payment result" (docs/ai/01 §1.11, derived from UC-S05 steps 6–7, A2, A3, E2, E3, BR-14, BR-30 — DDD §11
 * unavailable; ADR-011). Shared by the webhook, the lookups of T1 / UC-S05 / UC-S16 and the schedulers.
 * <p>Precondition (caller): one transaction, the order row locked {@code FOR UPDATE} first and the attempt row second,
 * both loaded for the first time under those locks (ADR-011 lock order, R1). Decision:
 * <ol>
 *   <li>failed result: a pending attempt → {@code failed} (A2); any other status → ignored.</li>
 *   <li>success result on an attempt already {@code success} or {@code needs_review} → ignored (E3, AC-11: idempotent
 *   by reference).</li>
 *   <li>amount ≠ attempt amount, or the provider's order code ≠ the attempt's order → {@code needs_review}, no access
 *   (E2, BR-14, AC-19).</li>
 *   <li>late result (attempt not pending or order not pending, A3): the student owns ANY course of the order, or the
 *   order is already paid → {@code needs_review} (BR-30, AC-24); else paid like on time (AC-23).</li>
 *   <li>on time (attempt and order pending): every course already owned → {@code needs_review} (P11 decision Q3:
 *   money arrived, nothing to grant); some owned → step 7 as written (grant the others), logged WARN (ADR-011
 *   double-payment risk).</li>
 *   <li>paid: attempt {@code success}, order {@code paid}, purchase enrollments for the courses not owned
 *   ({@link EnrollmentApi#grantForPaidOrder}, same transaction, NFR-04), the order's courses leave the cart, event
 *   {@link OrderPaid} (mail after commit, BR-20).</li>
 * </ol>
 */
@Component
class PaymentResultApplier {

    private static final Logger log = LoggerFactory.getLogger(PaymentResultApplier.class);

    enum Outcome { IGNORED, FAILED, NEEDS_REVIEW, PAID }

    private final EnrollmentApi enrollments;
    private final CartRepository carts;
    private final CartItemRepository cartItems;
    private final PaymentTransactionRepository payments;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    PaymentResultApplier(EnrollmentApi enrollments, CartRepository carts, CartItemRepository cartItems,
                         PaymentTransactionRepository payments, ApplicationEventPublisher events, Clock clock) {
        this.enrollments = enrollments;
        this.carts = carts;
        this.cartItems = cartItems;
        this.payments = payments;
        this.events = events;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    Outcome apply(Order order, PaymentTransaction payment, PaymentResult result) {
        Instant now = clock.instant();
        String ref = payment.getTransactionRef();
        String code = order.getOrderCode();
        if (!result.success()) {
            if (payment.isPending()) {
                payment.markFailed(now);
                log.info("Payment failed: transactionRef={}, orderCode={}", ref, code);
                return Outcome.FAILED;
            }
            log.info("Payment failure ignored (attempt {}): transactionRef={}, orderCode={}",
                    payment.getStatus().code(), ref, code);
            return Outcome.IGNORED;
        }
        if (payment.getStatus() == PaymentStatus.SUCCESS || payment.getStatus() == PaymentStatus.NEEDS_REVIEW) {
            log.info("Repeated payment result ignored (attempt {}): transactionRef={}, orderCode={}",
                    payment.getStatus().code(), ref, code);
            return Outcome.IGNORED;
        }
        if (result.amount() != payment.getAmount()
                || (result.orderCode() != null && !result.orderCode().equals(code))) {
            payment.flagNeedsReview(now);
            log.warn("Payment needs review (amount or order mismatch): transactionRef={}, orderCode={}, "
                    + "expectedAmount={}, receivedAmount={}, orderCodeMatches={}", ref, code, payment.getAmount(),
                    result.amount(), result.orderCode() == null || result.orderCode().equals(code));
            return Outcome.NEEDS_REVIEW;
        }
        List<Long> courseIds = order.courseIds();
        Set<Long> owned = enrollments.findOwnedCourseIds(order.getStudentId(), courseIds);
        boolean onTime = payment.isPending() && order.isPending();
        if (order.isPaid() || (!onTime && !owned.isEmpty()) || (onTime && owned.containsAll(courseIds))) {
            payment.flagNeedsReview(now);
            log.warn("Payment needs review (ownership conflict, late={}): transactionRef={}, orderCode={}, "
                    + "receivedAmount={}, ownedCourseIds={}", !onTime, ref, code, result.amount(), owned);
            return Outcome.NEEDS_REVIEW;
        }
        if (!owned.isEmpty()) {
            log.warn("Payment on time for an order with already owned courses (granting the others): "
                    + "transactionRef={}, orderCode={}, ownedCourseIds={}", ref, code, owned);
        }
        payment.markSuccess(now);
        order.markPaid(now);
        Set<Long> granted = fulfil(order, payment, now);
        events.publishEvent(new OrderPaid(order.getId(), code, order.getStudentId(), order.getTotalAmount(),
                courseIds, now));
        log.info("Order paid: transactionRef={}, orderCode={}, late={}, grantedCourseIds={}", ref, code, !onTime,
                granted);
        return Outcome.PAID;
    }

    /**
     * UC-S05 step 7 / UC-A18 step 4 (a), the part after the status changes: the order's other pending attempts are
     * closed (R-05), purchase enrollments for the order's courses
     * not owned yet (same transaction, ADR-004) and those courses leave the student's cart (plain cart-id read, no
     * {@code carts} lock — ADR-011). Returns the newly enrolled course ids.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    Set<Long> fulfil(Order order, PaymentTransaction paidBy, Instant now) {
        closeOtherPendingAttempts(order, paidBy, now);
        List<Long> courseIds = order.courseIds();
        Set<Long> granted = enrollments.grantForPaidOrder(
                new GrantEnrollmentCommand(order.getStudentId(), order.getId(), courseIds, now));
        carts.findIdByStudentId(order.getStudentId())
                .ifPresent(cartId -> cartItems.deleteByCartIdAndCourseIds(cartId, courseIds));
        return granted;
    }

    /**
     * Review P11-R-05: an order that became paid keeps no other {@code pending} attempt (a second QR made after the first
     * expired). They are closed as {@code expired} in the same transaction; a result arriving for one of them later is a
     * late result on a paid order → {@code needs_review}, never a second grant. The order row is locked by the caller,
     * and every flow touching an attempt locks its order first (ADR-011).
     */
    private void closeOtherPendingAttempts(Order order, PaymentTransaction paidBy, Instant now) {
        for (PaymentTransaction other : payments.findByOrderIdOrderByCreatedAtDescIdDesc(order.getId())) {
            if (other.isPending() && !other.getId().equals(paidBy.getId())) {
                other.expire(now);
                log.info("Other pending attempt of a paid order closed: transactionRef={}, orderCode={}",
                        other.getTransactionRef(), order.getOrderCode());
            }
        }
    }
}