package com.edumy.commerce.mapper;

import com.edumy.authoring.api.CourseSummary;
import com.edumy.commerce.dto.response.AdminOrderResponse;
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
