package com.edumy.commerce.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

/** DB codes must match the CHECK lists of V3__commerce.sql exactly. */
class CommerceConvertersTest {

    @Test
    void orderStatus_codes_matchCkOrdersStatus() {
        OrderStatusConverter converter = new OrderStatusConverter();
        assertThat(Arrays.stream(OrderStatus.values()).map(converter::convertToDatabaseColumn))
                .containsExactly("pending", "paid", "cancelled");
        assertThat(converter.convertToEntityAttribute("cancelled")).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void cancelReason_codes_matchCkOrdersCancelReason() {
        CancelReasonConverter converter = new CancelReasonConverter();
        assertThat(Arrays.stream(CancelReason.values()).map(converter::convertToDatabaseColumn))
                .containsExactly("user", "replaced", "expired");
        assertThat(converter.convertToEntityAttribute("replaced")).isEqualTo(CancelReason.REPLACED);
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }

    @Test
    void paymentStatus_codes_matchCkPaymentStatus() {
        PaymentStatusConverter converter = new PaymentStatusConverter();
        assertThat(Arrays.stream(PaymentStatus.values()).map(converter::convertToDatabaseColumn))
                .containsExactly("pending", "success", "failed", "expired", "needs_review");
        assertThat(converter.convertToEntityAttribute("needs_review")).isEqualTo(PaymentStatus.NEEDS_REVIEW);
    }

    @Test
    void handledAction_codes_matchCkPaymentAction() {
        HandledActionConverter converter = new HandledActionConverter();
        assertThat(Arrays.stream(HandledAction.values()).map(converter::convertToDatabaseColumn))
                .containsExactly("activated", "refund_outside");
        assertThat(converter.convertToEntityAttribute("refund_outside")).isEqualTo(HandledAction.REFUND_OUTSIDE);
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }
}
