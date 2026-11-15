package com.edumy.commerce.mapper;

import com.edumy.commerce.dto.response.AdminPaymentResponse;
import com.edumy.commerce.dto.response.PaymentQrResponse;
import com.edumy.commerce.dto.response.PaymentStatusResponse;
import com.edumy.commerce.entity.HandledAction;
import com.edumy.commerce.entity.Order;
import com.edumy.commerce.entity.PaymentStatus;
import com.edumy.commerce.entity.PaymentTransaction;
import com.edumy.commerce.repository.PaymentTransactionRepository.AdminPaymentView;
import com.edumy.identity.api.UserSummary;
import java.util.Map;

import org.mapstruct.Mapper;

/** Payment attempts → UC-S05 polling and UC-A14 responses. Statuses are written as lower-case codes. */
@Mapper(componentModel = "spring")
public interface PaymentMapper {

    /** UC-S05 polling: the QR payload is only shown while the attempt is still pending. */
    default PaymentStatusResponse toStatus(Order order, PaymentTransaction payment) {
        boolean pending = payment.getStatus() == PaymentStatus.PENDING;
        return new PaymentStatusResponse(order.getOrderCode(), order.getStatus().code(), payment.getTransactionRef(),
                payment.getStatus().code(), payment.getAmount(), pending ? payment.getQrData() : null,
                payment.getQrExpiresAt(), payment.getConfirmedAt(), payment.getCreatedAt());
    }

    /** UC-A14 row; names from {@code identity::api} by user id ({@code null} = account gone). */
    default AdminPaymentResponse toAdmin(AdminPaymentView payment, Map<Long, UserSummary> users) {
        UserSummary student = users.get(payment.getStudentId());
        UserSummary handler = payment.getHandledBy() == null ? null : users.get(payment.getHandledBy());
        return new AdminPaymentResponse(payment.getId(), payment.getOrderId(), payment.getOrderCode(),
                new AdminPaymentResponse.Student(payment.getStudentId(), student == null ? null : student.fullName(),
                        student == null ? null : student.email()),
                payment.getTransactionRef(), payment.getAmount(), payment.getQrExpiresAt(),
                payment.getStatus().code(), payment.getConfirmedAt(),
                payment.getHandledBy() == null ? null
                        : new AdminPaymentResponse.Handler(payment.getHandledBy(),
                                handler == null ? null : handler.fullName()),
                code(payment.getHandledAction()), payment.getHandledNote(), payment.getHandledAt(),
                payment.getCreatedAt());
    }

    /** UC-S05: the pending attempt as the QR response (a stored one being reused, or a sample). */
    default PaymentQrResponse toQr(String orderCode, PaymentTransaction payment) {
        return new PaymentQrResponse(orderCode, payment.getTransactionRef(), payment.getStatus().code(),
                payment.getAmount(), payment.getQrData(), payment.getQrExpiresAt(), payment.getCreatedAt());
    }

    private String code(HandledAction action) {
        return action == null ? null : action.code();
    }
}
