package com.edumy.commerce.controller;

import com.edumy.commerce.dto.request.CancelOrderRequest;
import com.edumy.commerce.dto.request.CreateOrderRequest;
import com.edumy.commerce.dto.response.OrderDetailResponse;
import com.edumy.commerce.dto.response.OrderSummaryResponse;
import com.edumy.commerce.service.OrderQueryService;
import com.edumy.commerce.service.OrderService;
import com.edumy.shared.security.CurrentUserId;
import com.edumy.shared.web.StubSupport;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The caller's orders (UC-S04, S06, S07, S16). Scoped by the caller's id; another student's order is a 404. */
@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "commerce")
public class OrderController {

    private final OrderQueryService queries;
    private final OrderService orders;

    public OrderController(OrderQueryService queries, OrderService orders) {
        this.queries = queries;
        this.orders = orders;
    }

    /** {@code page} (0-based), {@code size} (default 20, max 100); newest first ({@code sort} is ignored). */
    @GetMapping
    @Operation(summary = "UC-S06 Xem lịch sử đơn hàng (mới nhất trước)")
    public PagedModel<OrderSummaryResponse> list(@CurrentUserId Long userId, @ParameterObject Pageable pageable) {
        return new PagedModel<>(queries.listMine(userId, pageable));
    }

    @GetMapping("/{orderCode}")
    @Operation(summary = "UC-S07 Xem chi tiết đơn hàng (giá chốt, các lần giao dịch)")
    public OrderDetailResponse detail(@PathVariable String orderCode, @CurrentUserId Long userId) {
        return queries.getMine(userId, orderCode);
    }

    /**
     * Phase-1 stub, header {@code X-Edumy-Stub: true}: 201 + the order the caller's cart WOULD become (code
     * {@code ORD-<yyyyMMdd>-STUB}, status {@code pending}, purchasable lines at the current price, total); nothing is
     * inserted. 403 {@code EMAIL_NOT_VERIFIED} (D-02) · 422 {@code CART_EMPTY} (no purchasable line, UC-S04 E1).
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "UC-S04 Tạo đơn hàng từ giỏ hàng (STUB-P1: trả đơn mẫu tính từ giỏ hiện tại, không ghi dữ liệu)",
            description = "Phase-1 stub: returns the order preview (X-Edumy-Stub: true), nothing is inserted.")
    public OrderDetailResponse create(@CurrentUserId Long userId,
                                      @Valid @RequestBody(required = false) CreateOrderRequest request,
                                      HttpServletResponse response) {
        StubSupport.mark(response);
        // STUB-P1: replace with OrderService.create (T1 insert order + lines) — UC-S04, BR-12, BR-27, BR-29
        return orders.create(userId, request);
    }

    /**
     * Phase-1 stub, header {@code X-Edumy-Stub: true}: 200 + the order as it WOULD look cancelled (reason {@code user});
     * nothing is saved. 404 for an unknown or foreign code · 409 {@code DATA_CONFLICT} when the order is not pending.
     */
    @PostMapping("/{orderCode}/cancel")
    @Operation(summary = "UC-S16 Hủy đơn chờ thanh toán (STUB-P1: trả đơn như sau khi hủy, không ghi dữ liệu)",
            description = "Phase-1 stub: returns the cancelled-order preview (X-Edumy-Stub: true), nothing is saved.")
    public OrderDetailResponse cancel(@PathVariable String orderCode, @CurrentUserId Long userId,
                                      @Valid @RequestBody(required = false) CancelOrderRequest request,
                                      HttpServletResponse response) {
        StubSupport.mark(response);
        // STUB-P1: replace with OrderService.cancel (provider lookup, cancel order, expire attempt) — UC-S16, BR-29, BR-30
        return orders.cancel(userId, orderCode);
    }
}
