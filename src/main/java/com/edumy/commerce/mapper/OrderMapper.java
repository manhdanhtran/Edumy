package com.edumy.commerce.mapper;

import com.edumy.authoring.api.CourseSummary;
import com.edumy.commerce.dto.response.AdminOrderResponse;
import com.edumy.commerce.dto.response.CartResponse;
import com.edumy.commerce.dto.response.OrderDetailResponse;
import com.edumy.commerce.dto.response.OrderSummaryResponse;
import com.edumy.commerce.entity.CancelReason;
import com.edumy.commerce.entity.Order;
import com.edumy.commerce.entity.OrderItem;
import com.edumy.commerce.entity.OrderStatus;
import com.edumy.commerce.entity.PaymentStatus;
import com.edumy.commerce.entity.PaymentTransaction;
import com.edumy.commerce.repository.OrderRepository.OrderSummaryView;
import com.edumy.identity.api.UserSummary;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/** Orders → UC-S06 / UC-S07 / UC-A13 responses. Statuses are written as lower-case codes. */
@Mapper(componentModel = "spring")
public interface OrderMapper {

    /** UC-S06 row. */
    OrderSummaryResponse toSummary(OrderSummaryView order);

    /** UC-A13 row; {@code student} from {@code identity::api} ({@code null} = account gone). */
    @Mapping(target = "id", source = "order.id")
    @Mapping(target = "status", source = "order.status")
    @Mapping(target = "student", expression = "java(new AdminOrderResponse.Student("
            + "order.getStudentId(), student == null ? null : student.fullName(), "
            + "student == null ? null : student.email()))")
    AdminOrderResponse toAdmin(OrderSummaryView order, UserSummary student);

    /**
     * UC-S07: lines carry the frozen {@code unit_price} (BR-12); title / slug / thumbnail come from the course summary
     * (a missing course, impossible by FK, leaves them empty).
     */
    default OrderDetailResponse toDetail(Order order, Map<Long, CourseSummary> courses,
                                         List<PaymentTransaction> payments) {
        List<OrderDetailResponse.Item> items = order.getItems().stream()
                .map(line -> toItem(line, courses.get(line.getCourseId())))
                .toList();
        return new OrderDetailResponse(order.getOrderCode(), order.getTotalAmount(), order.getStatus().code(),
                code(order.getCancelReason()), order.getCreatedAt(), order.getPaidAt(), order.getCancelledAt(), items,
                payments.stream().map(this::toPayment).toList());
    }

    /**
     * UC-S04 preview (phase-1 stub): the pending order the cart WOULD become, lines at the current price (the price that
     * would be frozen, BR-12), total = Σ unit prices (BR-27); nothing is stored, no payment attempt yet.
     */
    default OrderDetailResponse toPreview(String orderCode, Instant now, List<CartResponse.Item> lines) {
        List<OrderDetailResponse.Item> items = lines.stream()
                .map(line -> new OrderDetailResponse.Item(line.courseId(), line.title(), line.slug(),
                        line.thumbnailUrl(), line.price()))
                .toList();
        long total = items.stream().mapToLong(OrderDetailResponse.Item::unitPrice).sum();
        return new OrderDetailResponse(orderCode, total, OrderStatus.PENDING.code(), null, now, null, null, items,
                List.of());
    }

    /**
     * UC-S16 preview (phase-1 stub): the order as it WOULD look right after the student cancelled it: status
     * {@code cancelled}, reason {@code user}, {@code cancelledAt} = now, the pending QR attempt closed as
     * {@code expired} (step 2); lines, total and every other attempt unchanged.
     */
    default OrderDetailResponse toCancelledPreview(OrderDetailResponse current, Instant now) {
        List<OrderDetailResponse.Payment> payments = current.payments().stream()
                .map(p -> PaymentStatus.PENDING.code().equals(p.status())
                        ? new OrderDetailResponse.Payment(p.transactionRef(), p.amount(),
                                PaymentStatus.EXPIRED.code(), p.qrExpiresAt(), p.confirmedAt(), p.createdAt())
                        : p)
                .toList();
        return new OrderDetailResponse(current.orderCode(), current.totalAmount(), OrderStatus.CANCELLED.code(),
                CancelReason.USER.code(), current.createdAt(), current.paidAt(), now, current.items(), payments);
    }

    private OrderDetailResponse.Item toItem(OrderItem line, CourseSummary course) {
        return new OrderDetailResponse.Item(line.getCourseId(), course == null ? null : course.title(),
                course == null ? null : course.slug(), course == null ? null : course.thumbnailUrl(),
                line.getUnitPrice());
    }

    private OrderDetailResponse.Payment toPayment(PaymentTransaction payment) {
        return new OrderDetailResponse.Payment(payment.getTransactionRef(), payment.getAmount(),
                payment.getStatus().code(), payment.getQrExpiresAt(), payment.getConfirmedAt(),
                payment.getCreatedAt());
    }

    default String code(OrderStatus status) {
        return status == null ? null : status.code();
    }

    default String code(CancelReason reason) {
        return reason == null ? null : reason.code();
    }

    default String code(PaymentStatus status) {
        return status == null ? null : status.code();
    }
}
