package com.edumy.commerce.controller;

import com.edumy.commerce.dto.request.AdminOrderSearchRequest;
import com.edumy.commerce.dto.request.AdminPaymentSearchRequest;
import com.edumy.commerce.dto.response.AdminOrderResponse;
import com.edumy.commerce.dto.response.AdminPaymentResponse;
import com.edumy.commerce.service.OrderQueryService;
import com.edumy.commerce.service.PaymentQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PagedModel;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Order and payment administration (UC-A13, UC-A14, UC-A18). Admin only: route rule {@code /api/v1/admin/**} plus the
 * method-level check (docs/ai/04 §4.3).
 */
@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "commerce")
@PreAuthorize("hasRole('ADMIN')")
public class AdminOrderController {

    private final OrderQueryService orders;
    private final PaymentQueryService payments;

    public AdminOrderController(OrderQueryService orders, PaymentQueryService payments) {
        this.orders = orders;
        this.payments = payments;
    }

    /** {@code page}, {@code size} (default 20, max 100); newest first ({@code sort} is ignored). */
    @GetMapping("/orders")
    @Operation(summary = "UC-A13 Xem danh sách đơn hàng (tra cứu theo mã đơn, email, trạng thái, khoảng thời gian)")
    public PagedModel<AdminOrderResponse> orders(@Valid @ParameterObject @ModelAttribute AdminOrderSearchRequest filter,
                                                 @ParameterObject Pageable pageable) {
        return new PagedModel<>(orders.search(filter, pageable));
    }

    /** Newest first; {@code awaitingReview=true} = the unhandled reconciliation queue, oldest first. */
    @GetMapping("/payments")
    @Operation(summary = "UC-A14 Xem trạng thái giao dịch (lọc trạng thái, giao dịch chờ đối soát chưa xử lý)")
    public PagedModel<AdminPaymentResponse> payments(
            @Valid @ParameterObject @ModelAttribute AdminPaymentSearchRequest filter,
            @ParameterObject Pageable pageable) {
        return new PagedModel<>(payments.search(filter, pageable));
    }
}
