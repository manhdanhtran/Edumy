package com.edumy.commerce.controller;

import com.edumy.commerce.dto.request.AdminOrderSearchRequest;
import com.edumy.commerce.dto.request.AdminPaymentSearchRequest;
import com.edumy.commerce.dto.request.ResolvePaymentRequest;
import com.edumy.commerce.dto.response.AdminOrderResponse;
import com.edumy.commerce.dto.response.AdminPaymentResponse;
import com.edumy.commerce.service.OrderQueryService;
import com.edumy.commerce.service.OrderSearchCriteria;
import com.edumy.commerce.service.PaymentQueryService;
import com.edumy.commerce.service.PaymentSearchCriteria;
import com.edumy.commerce.service.ReconciliationService;
import com.edumy.shared.security.CurrentUserId;
import com.edumy.shared.web.StubSupport;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PagedModel;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
    private final ReconciliationService reconciliation;

    public AdminOrderController(OrderQueryService orders, PaymentQueryService payments,
                                ReconciliationService reconciliation) {
        this.orders = orders;
        this.payments = payments;
        this.reconciliation = reconciliation;
    }

    /** {@code page}, {@code size} (default 20, max 100); newest first ({@code sort} is ignored). */
    @GetMapping("/orders")
    @Operation(summary = "UC-A13 Xem danh sách đơn hàng (tra cứu theo mã đơn, email, trạng thái, khoảng thời gian)")
    public PagedModel<AdminOrderResponse> orders(@Valid @ParameterObject @ModelAttribute AdminOrderSearchRequest filter,
                                                 @ParameterObject Pageable pageable) {
        return new PagedModel<>(orders.search(new OrderSearchCriteria(filter.orderCode(), filter.email(),
                filter.status(), filter.createdFrom(), filter.createdTo()), pageable));
    }

    /** Newest first; {@code awaitingReview=true} = the unhandled reconciliation queue, oldest first. */
    @GetMapping("/payments")
    @Operation(summary = "UC-A14 Xem trạng thái giao dịch (lọc trạng thái, giao dịch chờ đối soát chưa xử lý)")
    public PagedModel<AdminPaymentResponse> payments(
            @Valid @ParameterObject @ModelAttribute AdminPaymentSearchRequest filter,
            @ParameterObject Pageable pageable) {
        return new PagedModel<>(payments.search(
                new PaymentSearchCriteria(filter.status(), filter.onlyAwaitingReview()), pageable));
    }

    /**
     * Phase-1 stub, header {@code X-Edumy-Stub: true}: 200 + the attempt as it WOULD look once handled (handler, action,
     * note, time); nothing is saved. 403 when the admin role is no longer active in the database · 404 unknown id · 409
     * {@code DATA_CONFLICT} when the attempt is not an unhandled {@code needs_review} (E1) or {@code activated} but every
     * course is already owned (E2).
     */
    @PostMapping("/payments/{id}/resolve")
    @Operation(summary = "UC-A18 Xử lý giao dịch cần đối soát (STUB-P1: trả kết quả như sau khi xử lý, không ghi dữ liệu)",
            description = "Phase-1 stub: returns the payment as it would look after handling (X-Edumy-Stub: true), nothing is saved.")
    public AdminPaymentResponse resolve(@PathVariable Long id, @CurrentUserId Long adminId,
                                        @Valid @RequestBody ResolvePaymentRequest request,
                                        HttpServletResponse response) {
        StubSupport.mark(response);
        // STUB-P1: replace with ReconciliationService.resolve (activate / refund outside, enroll, mail) — UC-A18, BR-26, BR-30
        return reconciliation.resolve(adminId, id, request);
    }
}
