package com.edumy.commerce.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;

import com.edumy.shared.error.BusinessException;
import com.edumy.shared.error.ErrorCode;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class OrderTest {

    static final Instant NOW = Instant.parse("2026-11-12T08:00:00Z");
    static final Instant LATER = NOW.plusSeconds(600);
    static final Long STUDENT = 8L;
    static final String CODE = "ORD-20261112-0001";

    static Order newOrder() {
        return Order.create(STUDENT, CODE, List.of(new Order.Line(7L, 399_000), new Order.Line(9L, 450_000)), NOW);
    }

    /** An order driven through the real transitions into {@code status} (cancelled = by the user). */
    static Order orderIn(OrderStatus status) {
        Order order = newOrder();
        switch (status) {
            case PENDING -> { }
            case PAID -> order.markPaid(NOW);
            case CANCELLED -> order.cancel(CancelReason.USER, NOW);
        }
        assertThat(order.getStatus()).isEqualTo(status);
        return order;
    }

    // ------------------------------------------------------------------------------------------------------- create

    @Test
    void create_linesWithLockedPrices_pendingWithTotal_BR27() {
        Order order = newOrder();

        assertThat(order.getStudentId()).isEqualTo(STUDENT);
        assertThat(order.getOrderCode()).isEqualTo(CODE);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.getTotalAmount()).isEqualTo(849_000);
        assertThat(order.getCreatedAt()).isEqualTo(NOW);
        assertThat(order.getPaidAt()).isNull();
        assertThat(order.getCancelledAt()).isNull();
        assertThat(order.getCancelReason()).isNull();
        assertThat(order.isPending()).isTrue();
        assertThat(order.isPaid()).isFalse();
        assertThat(order.isOwnedBy(STUDENT)).isTrue();
        assertThat(order.isOwnedBy(9L)).isFalse();
        assertThat(order.courseIds()).containsExactly(7L, 9L);
        assertThat(order.getItems()).allSatisfy(item -> assertThat(item.getOrder()).isSameAs(order));
        assertThat(order.getItems()).extracting(OrderItem::getUnitPrice).containsExactly(399_000L, 450_000L);
    }

    @Test
    void create_singleLine_totalEqualsPrice() {
        Order order = Order.create(STUDENT, CODE, List.of(new Order.Line(7L, 10_000)), NOW);

        assertThat(order.getTotalAmount()).isEqualTo(10_000);
        assertThat(order.getItems()).hasSize(1);
    }

    @Test
    void create_noLine_rejected_BR27() {
        assertThatThrownBy(() -> Order.create(STUDENT, CODE, List.of(), NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void create_sameCourseTwice_rejected_uqOrderItemsOrderCourse() {
        List<Order.Line> lines = List.of(new Order.Line(7L, 399_000), new Order.Line(7L, 399_000));

        assertThatThrownBy(() -> Order.create(STUDENT, CODE, lines, NOW)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void create_priceNotPositive_rejected_ckOrderItemsPrice() {
        assertThatThrownBy(() -> Order.create(STUDENT, CODE, List.of(new Order.Line(7L, 0)), NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Order.create(STUDENT, CODE, List.of(new Order.Line(7L, -1)), NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void create_invalidOrderCode_rejected() {
        List<Order.Line> lines = List.of(new Order.Line(7L, 399_000));
        assertThatThrownBy(() -> Order.create(STUDENT, null, lines, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Order.create(STUDENT, " ", lines, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Order.create(STUDENT, "X".repeat(31), lines, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(Order.create(STUDENT, "X".repeat(30), lines, NOW).getOrderCode()).hasSize(30);
    }

    @Test
    void create_nullArguments_rejected() {
        List<Order.Line> lines = List.of(new Order.Line(7L, 399_000));
        assertThatThrownBy(() -> Order.create(null, CODE, lines, NOW)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Order.create(STUDENT, CODE, null, NOW)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Order.create(STUDENT, CODE, lines, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Order.Line(null, 1)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void getItems_readOnlyView() {
        Order order = newOrder();

        assertThatThrownBy(() -> order.getItems().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    // ----------------------------------------------------------------------------------------------------- markPaid

    @Test
    void markPaid_pending_paid_UCS05() {
        Order order = newOrder();

        order.markPaid(LATER);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(order.getPaidAt()).isEqualTo(LATER);
        assertThat(order.getCancelledAt()).isNull();
        assertThat(order.getCancelReason()).isNull();
        assertThat(order.isPaid()).isTrue();
    }

    @Test
    void markPaid_cancelled_paidKeepsCancelData_BR30() {
        Order order = newOrder();
        order.cancel(CancelReason.EXPIRED, NOW.plusSeconds(60));

        order.markPaid(LATER);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(order.getPaidAt()).isEqualTo(LATER);
        assertThat(order.getCancelReason()).isEqualTo(CancelReason.EXPIRED);
        assertThat(order.getCancelledAt()).isEqualTo(NOW.plusSeconds(60));
    }

    @Test
    void markPaid_paid_rejected_final() {
        Order order = orderIn(OrderStatus.PAID);

        assertConflict(() -> order.markPaid(LATER));
        assertThat(order.getPaidAt()).isEqualTo(NOW);
    }

    @Test
    void markPaid_nullNow_rejected() {
        Order order = newOrder();
        assertThatThrownBy(() -> order.markPaid(null)).isInstanceOf(NullPointerException.class);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
    }

    // ------------------------------------------------------------------------------------------------------- cancel

    @ParameterizedTest
    @EnumSource(CancelReason.class)
    void cancel_pending_cancelledWithReason_UCS16(CancelReason reason) {
        Order order = newOrder();

        order.cancel(reason, LATER);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getCancelReason()).isEqualTo(reason);
        assertThat(order.getCancelledAt()).isEqualTo(LATER);
        assertThat(order.getPaidAt()).isNull();
        assertThat(order.isPending()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = "PENDING", mode = EnumSource.Mode.EXCLUDE)
    void cancel_notPending_rejected(OrderStatus status) {
        Order order = orderIn(status);

        assertConflict(() -> order.cancel(CancelReason.REPLACED, LATER));
        assertThat(order.getStatus()).isEqualTo(status);
    }

    @Test
    void cancel_latePaidOrder_rejected_paidIsFinal() {
        Order order = orderIn(OrderStatus.CANCELLED);
        order.markPaid(LATER);

        assertConflict(() -> order.cancel(CancelReason.USER, LATER.plusSeconds(1)));
        assertThat(order.getCancelReason()).isEqualTo(CancelReason.USER);
    }

    @Test
    void cancel_nullArguments_rejected() {
        Order order = newOrder();
        assertThatThrownBy(() -> order.cancel(null, NOW)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> order.cancel(CancelReason.USER, null)).isInstanceOf(NullPointerException.class);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void equals_byIdOnly() {
        Order a = newOrder();
        Order b = newOrder();

        assertThat(a).isEqualTo(a).isNotEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a.toString()).contains(CODE).doesNotContain("items");
    }

    static void assertConflict(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getCode()).isEqualTo(ErrorCode.DATA_CONFLICT));
    }
}
