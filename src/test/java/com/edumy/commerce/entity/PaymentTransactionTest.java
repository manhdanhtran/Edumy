package com.edumy.commerce.entity;

import static com.edumy.commerce.entity.OrderTest.NOW;
import static com.edumy.commerce.entity.OrderTest.assertConflict;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import com.edumy.shared.error.BusinessException;
import com.edumy.shared.error.ErrorCode;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.EnumSource.Mode;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class PaymentTransactionTest {

    static final Instant EXPIRES = NOW.plusSeconds(15 * 60);
    static final Instant LATER = NOW.plusSeconds(20 * 60);
    static final Long ADMIN = 1L;
    static final String REF = "FAKEPAY-20261112-0001";
    static final String QR = "FAKEQR|EDUMY|ORD-20261112-0001|849000";

    static PaymentTransaction newPayment() {
        Order order = OrderTest.newOrder();
        return PaymentTransaction.create(order, REF, order.getTotalAmount(), QR, EXPIRES, NOW);
    }

    /** Unhandled transaction driven through the real transitions into {@code status}. */
    static PaymentTransaction paymentIn(PaymentStatus status) {
        PaymentTransaction payment = newPayment();
        switch (status) {
            case PENDING -> { }
            case SUCCESS -> payment.markSuccess(NOW);
            case FAILED -> payment.markFailed(NOW);
            case EXPIRED -> payment.expire(EXPIRES);
            case NEEDS_REVIEW -> payment.flagNeedsReview(NOW);
        }
        assertThat(payment.getStatus()).isEqualTo(status);
        return payment;
    }

    static PaymentTransaction refunded() {
        PaymentTransaction payment = paymentIn(PaymentStatus.NEEDS_REVIEW);
        payment.refundOutside(ADMIN, "Đã hoàn tiền", LATER);
        return payment;
    }

    // ------------------------------------------------------------------------------------------------------- create

    @Test
    void create_pendingOrder_pendingQr_UCS05() {
        Order order = OrderTest.newOrder();

        PaymentTransaction payment = PaymentTransaction.create(order, REF, 849_000, QR, EXPIRES, NOW);

        assertThat(payment.getOrder()).isSameAs(order);
        assertThat(payment.getTransactionRef()).isEqualTo(REF);
        assertThat(payment.getAmount()).isEqualTo(849_000);
        assertThat(payment.getQrData()).isEqualTo(QR);
        assertThat(payment.getQrExpiresAt()).isEqualTo(EXPIRES);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(payment.getCreatedAt()).isEqualTo(NOW);
        assertThat(payment.getConfirmedAt()).isNull();
        assertThat(payment.isPending()).isTrue();
        assertThat(payment.isHandled()).isFalse();
        assertThat(payment.isAwaitingReview()).isFalse();
        assertHandledEmpty(payment);
    }

    @Test
    void create_amountDiffersFromOrderTotal_rejected_BR14() {
        Order order = OrderTest.newOrder();

        assertThatThrownBy(() -> PaymentTransaction.create(order, REF, 849_001, QR, EXPIRES, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PaymentTransaction.create(order, REF, 0, QR, EXPIRES, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void create_expiryNotAfterNow_rejected_ckPaymentQrExpiry() {
        Order order = OrderTest.newOrder();

        assertThatThrownBy(() -> PaymentTransaction.create(order, REF, 849_000, QR, NOW, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PaymentTransaction.create(order, REF, 849_000, QR, NOW.minusSeconds(1), NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "  "})
    void create_blankRefOrQr_rejected(String blank) {
        Order order = OrderTest.newOrder();

        assertThatThrownBy(() -> PaymentTransaction.create(order, blank, 849_000, QR, EXPIRES, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PaymentTransaction.create(order, REF, 849_000, blank, EXPIRES, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void create_refLongerThan100_rejected() {
        Order order = OrderTest.newOrder();

        assertThatThrownBy(() -> PaymentTransaction.create(order, "R".repeat(101), 849_000, QR, EXPIRES, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(PaymentTransaction.create(order, "R".repeat(100), 849_000, QR, EXPIRES, NOW).getTransactionRef())
                .hasSize(100);
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = "PENDING", mode = Mode.EXCLUDE)
    void create_orderNotPending_rejected_UCS05(OrderStatus status) {
        Order order = OrderTest.orderIn(status);

        assertConflict(() -> PaymentTransaction.create(order, REF, order.getTotalAmount(), QR, EXPIRES, NOW));
    }

    @Test
    void create_nullArguments_rejected() {
        Order order = OrderTest.newOrder();
        assertThatThrownBy(() -> PaymentTransaction.create(null, REF, 849_000, QR, EXPIRES, NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> PaymentTransaction.create(order, REF, 849_000, QR, null, NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> PaymentTransaction.create(order, REF, 849_000, QR, EXPIRES, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void isQrExpired_atAndAfterExpiry_BR13() {
        PaymentTransaction payment = newPayment();

        assertThat(payment.isQrExpired(EXPIRES.minusNanos(1000))).isFalse();
        assertThat(payment.isQrExpired(EXPIRES)).isTrue();
        assertThat(payment.isQrExpired(LATER)).isTrue();
    }

    // -------------------------------------------------------------------------------------------------- markSuccess

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = {"PENDING", "EXPIRED", "FAILED"})
    void markSuccess_pendingExpiredFailed_successConfirmed_BR30(PaymentStatus status) {
        PaymentTransaction payment = paymentIn(status);

        payment.markSuccess(LATER);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(payment.getConfirmedAt()).isEqualTo(LATER);
        assertHandledEmpty(payment);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = {"SUCCESS", "NEEDS_REVIEW"})
    void markSuccess_successOrNeedsReview_rejected(PaymentStatus status) {
        PaymentTransaction payment = paymentIn(status);

        assertConflict(() -> payment.markSuccess(LATER));
        assertThat(payment.getStatus()).isEqualTo(status);
    }

    // ------------------------------------------------------------------------------------------ markFailed / expire

    @Test
    void markFailed_pending_failed_UCS05A2() {
        PaymentTransaction payment = newPayment();

        payment.markFailed(LATER);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getConfirmedAt()).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = "PENDING", mode = Mode.EXCLUDE)
    void markFailed_notPending_rejected(PaymentStatus status) {
        PaymentTransaction payment = paymentIn(status);

        assertConflict(() -> payment.markFailed(LATER));
        assertThat(payment.getStatus()).isEqualTo(status);
    }

    @Test
    void expire_pending_expired_UCS05A1() {
        PaymentTransaction payment = newPayment();

        payment.expire(LATER);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.EXPIRED);
        assertThat(payment.getConfirmedAt()).isNull();
    }

    @Test
    void expire_pendingBeforeQrExpiry_expired_orderCancelled_UCS16() {
        PaymentTransaction payment = newPayment();

        payment.expire(NOW.plusSeconds(60));

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.EXPIRED);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = "PENDING", mode = Mode.EXCLUDE)
    void expire_notPending_rejected(PaymentStatus status) {
        PaymentTransaction payment = paymentIn(status);

        assertConflict(() -> payment.expire(LATER));
        assertThat(payment.getStatus()).isEqualTo(status);
    }

    // ---------------------------------------------------------------------------------------------- flagNeedsReview

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = {"PENDING", "EXPIRED", "FAILED"})
    void flagNeedsReview_pendingExpiredFailed_needsReview_UCS05E2(PaymentStatus status) {
        PaymentTransaction payment = paymentIn(status);

        payment.flagNeedsReview(LATER);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.NEEDS_REVIEW);
        assertThat(payment.getConfirmedAt()).isNull();
        assertThat(payment.isAwaitingReview()).isTrue();
        assertHandledEmpty(payment);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = {"SUCCESS", "NEEDS_REVIEW"})
    void flagNeedsReview_successOrNeedsReview_rejected(PaymentStatus status) {
        PaymentTransaction payment = paymentIn(status);

        assertConflict(() -> payment.flagNeedsReview(LATER));
        assertThat(payment.getStatus()).isEqualTo(status);
    }

    @Test
    void transitions_handledRefund_allRejected() {
        PaymentTransaction payment = refunded();

        assertConflict(() -> payment.markSuccess(LATER));
        assertConflict(() -> payment.markFailed(LATER));
        assertConflict(() -> payment.expire(LATER));
        assertConflict(() -> payment.flagNeedsReview(LATER));
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.NEEDS_REVIEW);
    }

    // --------------------------------------------------------------------------------------------- activate (A18 a)

    @Test
    void activate_unhandledNeedsReview_successHandled_UCA18() {
        PaymentTransaction payment = paymentIn(PaymentStatus.NEEDS_REVIEW);

        payment.activate(ADMIN, "  Sao kê xác nhận đã chuyển đủ  ", LATER);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(payment.getConfirmedAt()).isEqualTo(LATER);
        assertThat(payment.getHandledBy()).isEqualTo(ADMIN);
        assertThat(payment.getHandledAction()).isEqualTo(HandledAction.ACTIVATED);
        assertThat(payment.getHandledNote()).isEqualTo("Sao kê xác nhận đã chuyển đủ");
        assertThat(payment.getHandledAt()).isEqualTo(LATER);
        assertThat(payment.isHandled()).isTrue();
        assertThat(payment.isAwaitingReview()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = "NEEDS_REVIEW", mode = Mode.EXCLUDE)
    void activate_notNeedsReview_rejected(PaymentStatus status) {
        PaymentTransaction payment = paymentIn(status);

        assertConflict(() -> payment.activate(ADMIN, "Ghi chú", LATER));
        assertThat(payment.getStatus()).isEqualTo(status);
        assertHandledEmpty(payment);
    }

    @Test
    void activate_alreadyHandled_rejected_UCA18E1() {
        PaymentTransaction activated = paymentIn(PaymentStatus.NEEDS_REVIEW);
        activated.activate(ADMIN, "Lần 1", LATER);
        PaymentTransaction refunded = refunded();

        assertConflict(() -> activated.activate(2L, "Lần 2", LATER.plusSeconds(1)));
        assertConflict(() -> refunded.activate(2L, "Lần 2", LATER.plusSeconds(1)));
        assertThat(activated.getHandledNote()).isEqualTo("Lần 1");
        assertThat(refunded.getStatus()).isEqualTo(PaymentStatus.NEEDS_REVIEW);
        assertThat(refunded.getHandledAction()).isEqualTo(HandledAction.REFUND_OUTSIDE);
        assertThat(refunded.getConfirmedAt()).isNull();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " \n "})
    void activate_blankNote_rejected_D29(String note) {
        PaymentTransaction payment = paymentIn(PaymentStatus.NEEDS_REVIEW);

        assertNoteRequired(() -> payment.activate(ADMIN, note, LATER));
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.NEEDS_REVIEW);
        assertHandledEmpty(payment);
    }

    @Test
    void activate_nullAdminOrNow_rejected() {
        PaymentTransaction payment = paymentIn(PaymentStatus.NEEDS_REVIEW);
        assertThatThrownBy(() -> payment.activate(null, "Ghi chú", LATER)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> payment.activate(ADMIN, "Ghi chú", null)).isInstanceOf(NullPointerException.class);
        assertHandledEmpty(payment);
    }

    // ---------------------------------------------------------------------------------------- refundOutside (A18 b)

    @Test
    void refundOutside_unhandledNeedsReview_staysNeedsReviewHandled_UCA18() {
        PaymentTransaction payment = paymentIn(PaymentStatus.NEEDS_REVIEW);

        payment.refundOutside(ADMIN, "Đã hoàn 390.000 đ qua ngân hàng", LATER);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.NEEDS_REVIEW);
        assertThat(payment.getConfirmedAt()).isNull();
        assertThat(payment.getHandledBy()).isEqualTo(ADMIN);
        assertThat(payment.getHandledAction()).isEqualTo(HandledAction.REFUND_OUTSIDE);
        assertThat(payment.getHandledNote()).isEqualTo("Đã hoàn 390.000 đ qua ngân hàng");
        assertThat(payment.getHandledAt()).isEqualTo(LATER);
        assertThat(payment.isAwaitingReview()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = "NEEDS_REVIEW", mode = Mode.EXCLUDE)
    void refundOutside_notNeedsReview_rejected(PaymentStatus status) {
        PaymentTransaction payment = paymentIn(status);

        assertConflict(() -> payment.refundOutside(ADMIN, "Ghi chú", LATER));
        assertHandledEmpty(payment);
    }

    @Test
    void refundOutside_alreadyHandled_rejected_UCA18E1() {
        PaymentTransaction refunded = refunded();
        PaymentTransaction activated = paymentIn(PaymentStatus.NEEDS_REVIEW);
        activated.activate(ADMIN, "Kích hoạt", LATER);

        assertConflict(() -> refunded.refundOutside(2L, "Lần 2", LATER.plusSeconds(1)));
        assertConflict(() -> activated.refundOutside(2L, "Lần 2", LATER.plusSeconds(1)));
        assertThat(refunded.getHandledBy()).isEqualTo(ADMIN);
        assertThat(refunded.getHandledNote()).isEqualTo("Đã hoàn tiền");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void refundOutside_blankNote_rejected_D29(String note) {
        PaymentTransaction payment = paymentIn(PaymentStatus.NEEDS_REVIEW);

        assertNoteRequired(() -> payment.refundOutside(ADMIN, note, LATER));
        assertHandledEmpty(payment);
    }

    @Test
    void equals_byIdOnly() {
        PaymentTransaction a = newPayment();
        PaymentTransaction b = newPayment();

        assertThat(a).isEqualTo(a).isNotEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a.toString()).contains(REF).doesNotContain(QR);
    }

    /** ck_payment_handling_complete: all four handled_* columns empty. */
    static void assertHandledEmpty(PaymentTransaction payment) {
        assertThat(payment.getHandledBy()).isNull();
        assertThat(payment.getHandledAction()).isNull();
        assertThat(payment.getHandledNote()).isNull();
        assertThat(payment.getHandledAt()).isNull();
    }

    static void assertNoteRequired(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class, e -> {
            assertThat(e.getCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(e.getErrors()).singleElement().satisfies(v -> assertThat(v.field()).isEqualTo("note"));
        });
    }
}
