package com.edumy.commerce.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.edumy.commerce.entity.CancelReason;
import com.edumy.commerce.entity.Cart;
import com.edumy.commerce.entity.CartItem;
import com.edumy.commerce.entity.HandledAction;
import com.edumy.commerce.entity.Order;
import com.edumy.commerce.entity.OrderItem;
import com.edumy.commerce.entity.OrderStatus;
import com.edumy.commerce.entity.PaymentStatus;
import com.edumy.commerce.entity.PaymentTransaction;
import com.edumy.commerce.repository.OrderRepository.OrderSummaryView;
import com.edumy.commerce.repository.PaymentTransactionRepository.AdminPaymentView;
import com.edumy.support.MySqlSpringBootTest;
import com.edumy.support.SchemaFixtures;
import jakarta.persistence.EntityManager;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * Commerce repositories on real MySQL 8.4 (database {@code edumy}, no seed). Every test runs in a rolled-back
 * transaction; users and courses (other modules' tables, FK parents only) are inserted with plain SQL in the same
 * transaction and every order code / transaction ref is unique, so nothing is left in the shared database
 * (docs/ai/07). A test that provokes a constraint violation ends with it (the persistence context is unusable after).
 */
@MySqlSpringBootTest
@Transactional
class CommerceRepositoryIT {

    static final Instant NOW = Instant.parse("2026-02-10T09:15:30.654321Z");
    static final long QR_MINUTES = 15;

    @Autowired
    EntityManager em;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    CartRepository carts;
    @Autowired
    CartItemRepository cartItems;
    @Autowired
    OrderRepository orders;
    @Autowired
    PaymentTransactionRepository payments;

    SchemaFixtures fx;
    long student;
    long courseA;
    long courseB;

    @BeforeEach
    void setUp() {
        fx = new SchemaFixtures(jdbc);
        student = fx.user();
        courseA = fx.course();
        courseB = fx.course();
    }

    // -------------------------------------------------------------------------------------------------------- carts

    @Test
    void cart_withItems_roundTrip_addedOrder_removeIsOrphanDelete_UCS02() {
        Cart cart = Cart.create(student, NOW);
        cart.addItem(courseB, NOW.plusSeconds(1));
        cart.addItem(courseA, NOW.plusSeconds(2));
        carts.saveAndFlush(cart);
        em.clear();

        Cart loaded = carts.findWithItemsByStudentId(student).orElseThrow();
        assertThat(Hibernate.isInitialized(loaded.getItems())).isTrue();
        assertThat(loaded.getItems()).extracting(CartItem::getCourseId).containsExactly(courseB, courseA);
        assertThat(loaded.getItems().getFirst().getAddedAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(loaded.getCreatedAt()).isEqualTo(NOW);
        assertThat(loaded.getUpdatedAt()).isNotNull();
        assertThat(carts.findWithItemsByStudentId(fx.user())).isEmpty();
        assertThat(cartItems.countByCartStudentId(student)).isEqualTo(2);
        assertThat(cartItems.findByCartIdAndCourseId(cart.getId(), courseA)).isPresent();
        assertThat(cartItems.findByCartIdAndCourseId(cart.getId(), Long.MAX_VALUE)).isEmpty();

        assertThat(loaded.removeItem(courseB)).isTrue();
        carts.flush();
        assertThat(jdbc.sql("SELECT course_id FROM cart_items WHERE cart_id = ?").param(cart.getId())
                .query(Long.class).list()).containsExactly(courseA);
    }

    @Test
    void cart_duplicateCourse_violatesUqCartItemsCartCourse_BR11() {
        Cart cart = Cart.create(student, NOW);
        cart.addItem(courseA, NOW);
        carts.saveAndFlush(cart);

        assertThatThrownBy(() -> jdbc.sql("INSERT INTO cart_items (cart_id, course_id) VALUES (?, ?)")
                .params(cart.getId(), courseA).update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_cart_items_cart_course");
    }

    @Test
    void cart_secondCartOfStudent_violatesUqCartsStudent_BR22() {
        carts.saveAndFlush(Cart.create(student, NOW));

        assertThatThrownBy(() -> carts.saveAndFlush(Cart.create(student, NOW)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause().hasMessageContaining("uq_carts_student");
    }

    @Test
    void cart_lockForUpdate_removeBoughtCourses_T1T2() {
        Cart cart = Cart.create(student, NOW);
        cart.addItem(courseA, NOW);
        cart.addItem(courseB, NOW);
        carts.saveAndFlush(cart);
        em.clear();

        Cart locked = carts.findByStudentIdForUpdate(student).orElseThrow();
        assertThat(locked.removeCourses(List.of(courseA))).isEqualTo(1);
        carts.flush();

        assertThat(carts.findByStudentIdForUpdate(fx.user())).isEmpty();
        assertThat(jdbc.sql("SELECT course_id FROM cart_items WHERE cart_id = ?").param(cart.getId())
                .query(Long.class).list()).containsExactly(courseB);
    }

    // ------------------------------------------------------------------------------------------------------- orders

    @Test
    void order_withItems_persistedWithTotal_generatedColumnSet_BR27() {
        Order order = orders.saveAndFlush(newOrder(student, NOW));

        Map<String, Object> row = jdbc.sql("""
                SELECT status, total_amount, pending_student_id, cancel_reason, paid_at, cancelled_at,
                       DATE_FORMAT(created_at, '%Y-%m-%dT%H:%i:%s.%fZ') AS created
                FROM orders WHERE id = ?""").param(order.getId()).query().singleRow();
        assertThat(row.get("status")).isEqualTo("pending");
        assertThat(row.get("total_amount")).isEqualTo(849_000L);
        assertThat(row.get("pending_student_id")).isEqualTo(student);
        assertThat(row.get("cancel_reason")).isNull();
        assertThat(row.get("paid_at")).isNull();
        assertThat(row.get("cancelled_at")).isNull();
        assertThat(row.get("created")).isEqualTo(NOW.toString());
        assertThat(jdbc.sql("SELECT SUM(unit_price) FROM order_items WHERE order_id = ?").param(order.getId())
                .query(Long.class).single()).isEqualTo(849_000L);
        em.clear();

        Order loaded = orders.findWithItemsByOrderCodeAndStudentId(order.getOrderCode(), student).orElseThrow();
        assertThat(Hibernate.isInitialized(loaded.getItems())).isTrue();
        assertThat(loaded.getItems()).extracting(OrderItem::getCourseId, OrderItem::getUnitPrice)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(courseA, 399_000L),
                        org.assertj.core.groups.Tuple.tuple(courseB, 450_000L));
        assertThat(loaded.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(loaded.getCreatedAt()).isEqualTo(NOW);
        assertThat(loaded.getUpdatedAt()).isNotNull();
        assertThat(orders.existsByOrderCode(order.getOrderCode())).isTrue();
        assertThat(orders.existsByOrderCode(order.getOrderCode() + "X")).isFalse();
    }

    @Test
    void order_lookupScopedByStudent_otherStudentEmpty_NFR01() {
        long other = fx.user();
        Order order = orders.saveAndFlush(newOrder(student, NOW));
        em.clear();

        assertThat(orders.findByOrderCodeAndStudentId(order.getOrderCode(), student)).isPresent();
        assertThat(orders.findByOrderCodeAndStudentId(order.getOrderCode(), other)).isEmpty();
        assertThat(orders.findWithItemsByOrderCodeAndStudentId(order.getOrderCode(), other)).isEmpty();
        assertThat(orders.findWithItemsByOrderCodeAndStudentId("NO-SUCH-CODE", student)).isEmpty();
        assertThat(orders.findSummariesByStudentId(other, PageRequest.of(0, 10)).getTotalElements()).isZero();
    }

    @Test
    void order_replacedThenNewPending_ok_secondPendingViolatesUqOrdersOnePending_BR29() {
        Order replaced = orders.saveAndFlush(newOrder(student, NOW));
        Order lockedOld = orders.findPendingByStudentIdForUpdate(student).orElseThrow();
        assertThat(lockedOld).isSameAs(replaced);
        lockedOld.cancel(CancelReason.REPLACED, NOW.plusSeconds(60));
        orders.flush(); // IDENTITY inserts run at persist time: the cancel must reach the DB first
        orders.saveAndFlush(newOrder(student, NOW.plusSeconds(60)));
        assertThat(jdbc.sql("SELECT pending_student_id FROM orders WHERE id = ?").param(replaced.getId())
                .query(Long.class).optional()).isEmpty();
        Order otherStudentsPending = orders.saveAndFlush(newOrder(fx.user(), NOW));
        assertThat(otherStudentsPending.getId()).isNotNull();

        assertThatThrownBy(() -> orders.saveAndFlush(newOrder(student, NOW.plusSeconds(120))))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause().hasMessageContaining("uq_orders_one_pending");
    }

    @Test
    void order_latePaidCancelledOrder_persisted_ckOrdersState_BR30() {
        Order order = orders.saveAndFlush(newOrder(student, NOW));
        order.cancel(CancelReason.EXPIRED, NOW.plusSeconds(86_400));
        orders.flush();
        Order locked = orders.findByIdForUpdate(order.getId()).orElseThrow();
        locked.markPaid(NOW.plusSeconds(90_000));
        orders.flush();
        em.clear();

        Map<String, Object> row = jdbc.sql("""
                SELECT status, cancel_reason, pending_student_id,
                       paid_at IS NOT NULL AS has_paid, cancelled_at IS NOT NULL AS has_cancelled
                FROM orders WHERE id = ?""").param(order.getId()).query().singleRow();
        assertThat(row.get("status")).isEqualTo("paid");
        assertThat(row.get("cancel_reason")).isEqualTo("expired");
        assertThat(row.get("pending_student_id")).isNull();
        assertThat(row.get("has_paid")).isEqualTo(1L);
        assertThat(row.get("has_cancelled")).isEqualTo(1L);
        Order reloaded = orders.findById(order.getId()).orElseThrow();
        assertThat(reloaded.getPaidAt()).isEqualTo(NOW.plusSeconds(90_000));
        assertThat(reloaded.getCancelledAt()).isEqualTo(NOW.plusSeconds(86_400));
        assertThat(reloaded.getCancelReason()).isEqualTo(CancelReason.EXPIRED);
        assertThat(orders.findPendingByStudentIdForUpdate(student)).isEmpty();
    }

    @Test
    void findSummariesByStudentId_newestFirst_paged_itemCount_UCS06() {
        Order oldest = orders.save(newOrder(student, NOW));
        oldest.cancel(CancelReason.USER, NOW.plusSeconds(10));
        orders.flush();
        Order middle = orders.save(Order.create(student, code(), List.of(new Order.Line(courseA, 99_000)),
                NOW.plusSeconds(20)));
        middle.markPaid(NOW.plusSeconds(30));
        orders.flush();
        Order newest = orders.save(newOrder(student, NOW.plusSeconds(40)));
        orders.save(newOrder(fx.user(), NOW.plusSeconds(50)));
        em.flush();
        em.clear();

        Page<OrderSummaryView> page = orders.findSummariesByStudentId(student, PageRequest.of(0, 2));
        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent()).extracting(OrderSummaryView::getId)
                .containsExactly(newest.getId(), middle.getId());
        OrderSummaryView paid = page.getContent().get(1);
        assertThat(paid.getOrderCode()).isEqualTo(middle.getOrderCode());
        assertThat(paid.getStudentId()).isEqualTo(student);
        assertThat(paid.getTotalAmount()).isEqualTo(99_000);
        assertThat(paid.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(paid.getPaidAt()).isEqualTo(NOW.plusSeconds(30));
        assertThat(paid.getCreatedAt()).isEqualTo(NOW.plusSeconds(20));
        assertThat(paid.getItemCount()).isEqualTo(1);
        OrderSummaryView cancelled = orders.findSummariesByStudentId(student, PageRequest.of(1, 2)).getContent()
                .getFirst();
        assertThat(cancelled.getId()).isEqualTo(oldest.getId());
        assertThat(cancelled.getCancelReason()).isEqualTo(CancelReason.USER);
        assertThat(cancelled.getCancelledAt()).isEqualTo(NOW.plusSeconds(10));
        assertThat(cancelled.getItemCount()).isEqualTo(2);
    }

    @Test
    void searchForAdmin_optionalFilters_newestFirst_UCA13() {
        long other = fx.user();
        Order a = orders.save(newOrder(student, NOW));
        a.markPaid(NOW.plusSeconds(5));
        orders.flush();
        Order b = orders.save(newOrder(other, NOW.plusSeconds(10)));
        Order c = orders.save(newOrder(student, NOW.plusSeconds(20)));
        em.flush();
        em.clear();
        Set<Long> mine = Set.of(a.getId(), b.getId(), c.getId());

        assertThat(orders.searchForAdmin(null, null, null, null, null, PageRequest.of(0, 1000)).getContent())
                .extracting(OrderSummaryView::getId).filteredOn(mine::contains)
                .containsExactly(c.getId(), b.getId(), a.getId());
        assertThat(orders.searchForAdmin(OrderStatus.PENDING, null, student, null, null, PageRequest.of(0, 10))
                .getContent()).extracting(OrderSummaryView::getId).containsExactly(c.getId());
        assertThat(orders.searchForAdmin(null, b.getOrderCode().toLowerCase(), null, null, null,
                PageRequest.of(0, 10)).getContent()).extracting(OrderSummaryView::getId).containsExactly(b.getId());
        Page<OrderSummaryView> window = orders.searchForAdmin(null, null, null, NOW.plusSeconds(10),
                NOW.plusSeconds(20), PageRequest.of(0, 1000));
        assertThat(window.getContent()).extracting(OrderSummaryView::getId).filteredOn(mine::contains)
                .containsExactly(b.getId());
        Page<OrderSummaryView> paid = orders.searchForAdmin(OrderStatus.PAID, null, student, null, null,
                PageRequest.of(0, 10));
        assertThat(paid.getTotalElements()).isEqualTo(1);
        assertThat(paid.getContent().getFirst().getItemCount()).isEqualTo(2);
    }

    // ----------------------------------------------------------------------------------------------------- payments

    @Test
    void payment_persisted_queriesByOrder_currentAndPending_UCS05() {
        Order order = orders.save(newOrder(student, NOW));
        PaymentTransaction failed = payments.save(newPayment(order, NOW.plusSeconds(1)));
        failed.markFailed(NOW.plusSeconds(2));
        payments.flush();
        PaymentTransaction current = payments.saveAndFlush(newPayment(order, NOW.plusSeconds(3)));

        Map<String, Object> row = jdbc.sql("""
                SELECT status, amount, qr_data, pending_order_id, confirmed_at, handled_at,
                       DATE_FORMAT(qr_expires_at, '%Y-%m-%dT%H:%i:%s.%fZ') AS expires
                FROM payment_transactions WHERE id = ?""").param(current.getId()).query().singleRow();
        assertThat(row.get("status")).isEqualTo("pending");
        assertThat(row.get("amount")).isEqualTo(849_000L);
        assertThat(row.get("qr_data")).isEqualTo(current.getQrData());
        assertThat(row.get("pending_order_id")).isEqualTo(order.getId());
        assertThat(row.get("confirmed_at")).isNull();
        assertThat(row.get("handled_at")).isNull();
        assertThat(row.get("expires")).isEqualTo(NOW.plusSeconds(3).plusSeconds(QR_MINUTES * 60).toString());
        em.clear();

        assertThat(payments.findByOrderIdOrderByCreatedAtDescIdDesc(order.getId()))
                .extracting(PaymentTransaction::getId).containsExactly(current.getId(), failed.getId());
        assertThat(payments.findFirstByOrderIdOrderByCreatedAtDescIdDesc(order.getId()))
                .get().extracting(PaymentTransaction::getId).isEqualTo(current.getId());
        assertThat(payments.findPendingByOrderId(order.getId()))
                .get().extracting(PaymentTransaction::getId).isEqualTo(current.getId());
        assertThat(payments.findFirstByOrderIdOrderByCreatedAtDescIdDesc(Long.MAX_VALUE)).isEmpty();

        PaymentTransaction locked = payments.findByTransactionRefForUpdate(current.getTransactionRef()).orElseThrow();
        assertThat(locked.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(locked.getQrExpiresAt()).isEqualTo(NOW.plusSeconds(3 + QR_MINUTES * 60));
        assertThat(payments.findByTransactionRefForUpdate("NO-SUCH-REF")).isEmpty();
        locked.markSuccess(NOW.plusSeconds(60));
        locked.getOrder().markPaid(NOW.plusSeconds(60));
        payments.flush();
        assertThat(jdbc.sql("SELECT status FROM orders WHERE id = ?").param(order.getId()).query(String.class)
                .single()).isEqualTo("paid");
        assertThat(jdbc.sql("SELECT confirmed_at IS NOT NULL FROM payment_transactions WHERE id = ?")
                .param(current.getId()).query(Long.class).single()).isEqualTo(1L);
        assertThat(payments.findPendingByOrderId(order.getId())).isEmpty();
    }

    @Test
    void payment_secondPendingOfOrder_violatesUqPaymentOnePending_BR16() {
        Order order = orders.save(newOrder(student, NOW));
        PaymentTransaction expired = payments.saveAndFlush(newPayment(order, NOW));
        expired.expire(NOW.plusSeconds(QR_MINUTES * 60));
        payments.flush();
        payments.saveAndFlush(newPayment(order, NOW.plusSeconds(QR_MINUTES * 60)));

        assertThatThrownBy(() -> payments.saveAndFlush(newPayment(order, NOW.plusSeconds(QR_MINUTES * 60 + 1))))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause().hasMessageContaining("uq_payment_one_pending");
    }

    @Test
    void payment_activateAndRefundOutside_persisted_checksSatisfied_UCA18() {
        long admin = fx.user();
        Order cancelledOrder = orders.save(newOrder(student, NOW));
        PaymentTransaction toActivate = payments.save(newPayment(cancelledOrder, NOW));
        toActivate.expire(NOW.plusSeconds(QR_MINUTES * 60));
        cancelledOrder.cancel(CancelReason.EXPIRED, NOW.plusSeconds(86_400));
        toActivate.flagNeedsReview(NOW.plusSeconds(86_500));
        Order otherOrder = orders.save(newOrder(fx.user(), NOW));
        PaymentTransaction toRefund = payments.save(newPayment(otherOrder, NOW));
        toRefund.flagNeedsReview(NOW.plusSeconds(100));
        em.flush();
        em.clear();

        PaymentTransaction lockedA = payments.findByIdForUpdate(toActivate.getId()).orElseThrow();
        lockedA.activate(admin, "Sao kê xác nhận đã chuyển đủ", NOW.plusSeconds(90_000));
        orders.findByIdForUpdate(cancelledOrder.getId()).orElseThrow().markPaid(NOW.plusSeconds(90_000));
        payments.findByIdForUpdate(toRefund.getId()).orElseThrow()
                .refundOutside(admin, "Đã hoàn tiền ngoài hệ thống", NOW.plusSeconds(90_000));
        em.flush();
        em.clear();

        Map<String, Object> activated = handledRow(toActivate.getId());
        assertThat(activated.get("status")).isEqualTo("success");
        assertThat(activated.get("handled_action")).isEqualTo("activated");
        assertThat(activated.get("handled_by")).isEqualTo(admin);
        assertThat(activated.get("handled_note")).isEqualTo("Sao kê xác nhận đã chuyển đủ");
        assertThat(activated.get("has_handled_at")).isEqualTo(1L);
        assertThat(activated.get("has_confirmed_at")).isEqualTo(1L);
        Map<String, Object> refunded = handledRow(toRefund.getId());
        assertThat(refunded.get("status")).isEqualTo("needs_review");
        assertThat(refunded.get("handled_action")).isEqualTo("refund_outside");
        assertThat(refunded.get("has_handled_at")).isEqualTo(1L);
        assertThat(refunded.get("has_confirmed_at")).isEqualTo(0L);
        assertThat(jdbc.sql("SELECT status FROM orders WHERE id = ?").param(cancelledOrder.getId())
                .query(String.class).single()).isEqualTo("paid");

        PaymentTransaction reloaded = payments.findById(toActivate.getId()).orElseThrow();
        assertThat(reloaded.getHandledAction()).isEqualTo(HandledAction.ACTIVATED);
        assertThat(reloaded.getHandledAt()).isEqualTo(NOW.plusSeconds(90_000));
        assertThat(reloaded.getConfirmedAt()).isEqualTo(NOW.plusSeconds(90_000));
    }

    @Test
    void findReviewQueue_unhandledNeedsReviewOnly_oldestFirst_UCA18() {
        long admin = fx.user();
        Order order = orders.save(newOrder(student, NOW));
        PaymentTransaction third = reviewPayment(order, NOW.plusSeconds(300));
        PaymentTransaction first = reviewPayment(order, NOW.plusSeconds(100));
        PaymentTransaction handled = reviewPayment(order, NOW.plusSeconds(50));
        handled.refundOutside(admin, "Đã hoàn", NOW.plusSeconds(400));
        PaymentTransaction second = reviewPayment(order, NOW.plusSeconds(200));
        PaymentTransaction success = payments.save(newPayment(order, NOW.plusSeconds(10)));
        success.markSuccess(NOW.plusSeconds(20));
        em.flush();
        em.clear();
        Set<Long> mine = Set.of(first.getId(), second.getId(), third.getId(), handled.getId(), success.getId());

        Page<AdminPaymentView> queue = payments.findReviewQueue(PageRequest.of(0, 1000));
        assertThat(queue.getContent()).extracting(AdminPaymentView::getId).filteredOn(mine::contains)
                .containsExactly(first.getId(), second.getId(), third.getId());
        AdminPaymentView view = queue.getContent().stream().filter(v -> v.getId().equals(first.getId()))
                .findFirst().orElseThrow();
        assertThat(view.getOrderId()).isEqualTo(order.getId());
        assertThat(view.getOrderCode()).isEqualTo(order.getOrderCode());
        assertThat(view.getStudentId()).isEqualTo(student);
        assertThat(view.getTransactionRef()).isEqualTo(first.getTransactionRef());
        assertThat(view.getAmount()).isEqualTo(849_000);
        assertThat(view.getStatus()).isEqualTo(PaymentStatus.NEEDS_REVIEW);
        assertThat(view.getCreatedAt()).isEqualTo(NOW.plusSeconds(100));
        assertThat(view.getQrExpiresAt()).isEqualTo(NOW.plusSeconds(100 + QR_MINUTES * 60));
        assertThat(view.getHandledAt()).isNull();

        assertThat(payments.searchForAdmin(PaymentStatus.NEEDS_REVIEW, PageRequest.of(0, 1000)).getContent())
                .extracting(AdminPaymentView::getId).filteredOn(mine::contains)
                .containsExactly(third.getId(), second.getId(), first.getId(), handled.getId());
        AdminPaymentView handledView = payments.searchForAdmin(null, PageRequest.of(0, 1000)).getContent().stream()
                .filter(v -> v.getId().equals(handled.getId())).findFirst().orElseThrow();
        assertThat(handledView.getHandledBy()).isEqualTo(admin);
        assertThat(handledView.getHandledAction()).isEqualTo(HandledAction.REFUND_OUTSIDE);
        assertThat(handledView.getHandledNote()).isEqualTo("Đã hoàn");
        assertThat(handledView.getHandledAt()).isEqualTo(NOW.plusSeconds(400));
        assertThat(payments.searchForAdmin(PaymentStatus.SUCCESS, PageRequest.of(0, 1000)).getContent())
                .extracting(AdminPaymentView::getId).filteredOn(mine::contains).containsExactly(success.getId());
    }

    // ------------------------------------------------------------------------------------------------------ helpers

    Order newOrder(long studentId, Instant createdAt) {
        return Order.create(studentId, code(),
                List.of(new Order.Line(courseA, 399_000), new Order.Line(courseB, 450_000)), createdAt);
    }

    static PaymentTransaction newPayment(Order order, Instant createdAt) {
        long n = SchemaFixtures.next();
        return PaymentTransaction.create(order, "FAKEPAY-IT-" + n, order.getTotalAmount(),
                "FAKEQR|EDUMY|" + order.getOrderCode() + "|" + order.getTotalAmount(),
                createdAt.plusSeconds(QR_MINUTES * 60), createdAt);
    }

    PaymentTransaction reviewPayment(Order order, Instant createdAt) {
        PaymentTransaction payment = payments.save(newPayment(order, createdAt));
        payment.flagNeedsReview(createdAt.plusSeconds(1));
        payments.flush();
        return payment;
    }

    Map<String, Object> handledRow(long paymentId) {
        return jdbc.sql("""
                SELECT status, handled_by, handled_action, handled_note,
                       handled_at IS NOT NULL AS has_handled_at, confirmed_at IS NOT NULL AS has_confirmed_at
                FROM payment_transactions WHERE id = ?""").param(paymentId).query().singleRow();
    }

    static String code() {
        return "ORD-IT-" + SchemaFixtures.next();
    }
}
