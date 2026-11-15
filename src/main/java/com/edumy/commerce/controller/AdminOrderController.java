package com.edumy.commerce.controller;

import com.edumy.commerce.dto.request.AdminOrderSearchRequest;
import com.edumy.commerce.dto.request.AdminPaymentSearchRequest;
import com.edumy.commerce.dto.request.ResolvePaymentRequest;
import com.edumy.commerce.dto.response.AdminOrderResponse;
import com.edumy.commerce.dto.response.AdminPaymentDetailResponse;
import com.edumy.commerce.dto.response.AdminPaymentResponse;
import com.edumy.commerce.service.OrderQueryService;
import com.edumy.commerce.service.OrderSearchCriteria;
import com.edumy.commerce.service.PaymentQueryService;
import com.edumy.commerce.service.PaymentSearchCriteria;
import com.edumy.commerce.service.ReconciliationService;
import com.edumy.commerce.service.ResolvePaymentCommand;
import com.edumy.shared.security.CurrentUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
     * UC-A18 step 1 (P11 decision Q4): the attempt, its order lines, the courses the student already owns and the amount
     * the provider reports as paid now ({@code null} when nothing / unreachable). 404 unknown id.
     */
    @GetMapping("/payments/{id}")
    @Operation(summary = "UC-A18 Xem chi tiết giao dịch cần đối soát (dòng đơn, khóa đã sở hữu, số tiền nhận được)")
    public AdminPaymentDetailResponse payment(@PathVariable Long id) {
        return payments.getAdminDetail(id);
    }

    /**
     * UC-A18 (P11-d): 200 + the attempt once handled ({@code handledBy}, {@code handledAction}, {@code handledNote},
     * {@code handledAt}; {@code activated} → {@code success} + {@code confirmedAt}, order paid, enrollments;
     * {@code refund_outside} keeps {@code needs_review}). 403 when the admin role is no longer active in the database
     * (BR-26) · 404 unknown id · 409 {@code DATA_CONFLICT} when the attempt is not an unhandled {@code needs_review}
     * (E1) or {@code activated} with every course already owned (E2) · 400 blank note (E3).
     */
    @PostMapping("/payments/{id}/resolve")
    @Operation(summary = "UC-A18 Xử lý giao dịch cần đối soát (kích hoạt hoặc hoàn tiền ngoài hệ thống, ghi chú bắt buộc)")
    public AdminPaymentResponse resolve(@PathVariable Long id, @CurrentUserId Long adminId,
                                        @Valid @RequestBody ResolvePaymentRequest request) {
        return reconciliation.resolve(new ResolvePaymentCommand(adminId, id, request.action(), request.note()));
    }
}