package com.edumy.commerce.controller;

import com.edumy.commerce.dto.request.CancelOrderRequest;
import com.edumy.commerce.dto.request.CreateOrderRequest;
import com.edumy.commerce.dto.response.OrderDetailResponse;
import com.edumy.commerce.dto.response.OrderSummaryResponse;
import com.edumy.commerce.service.OrderQueryService;
import com.edumy.commerce.service.CreateOrderCommand;
import com.edumy.commerce.service.OrderService;
import com.edumy.shared.security.CurrentUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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
     * UC-S04 (T1, P11-b): 201 + the new pending order (lines at the locked price, BR-12; total, BR-27), or 200 + the
     * identical pending order the student already has (E3: concurrent / repeated request). 403 {@code EMAIL_NOT_VERIFIED}
     * (D-02) · 422 {@code CART_EMPTY} (no purchasable line, E1) · 409 {@code ORDER_CART_CHANGED} (expected lines / total
     * no longer true, step 3) · 409 {@code ORDER_ALREADY_PAID} (the previous pending order turned out paid: recorded,
     * reload the cart) · 503 {@code PAYMENT_PROVIDER_UNAVAILABLE} (the previous order could not be looked up).
     */
    @PostMapping
    @Operation(summary = "UC-S04 Tạo đơn hàng từ giỏ hàng (giá chốt; thay đơn chờ cũ sau tra cứu)")
    public ResponseEntity<OrderDetailResponse> create(@CurrentUserId Long userId,
                                                      @Valid @RequestBody(required = false) CreateOrderRequest request) {
        CreateOrderCommand command = request == null ? CreateOrderCommand.unchecked()
                : new CreateOrderCommand(request.expectedTotalAmount(), request.expectedCourseIds());
        OrderService.CreateOutcome outcome = orders.create(userId, command);
        return ResponseEntity.status(outcome.created() ? HttpStatus.CREATED : HttpStatus.OK).body(outcome.order());
    }

    /**
     * UC-S16 (P11-b): 200 + the order, now {@code cancelled} ({@code user}); its pending attempt was looked up and
     * closed. 404 for an unknown or foreign code · 409 {@code DATA_CONFLICT} when the order is not pending · 409
     * {@code ORDER_ALREADY_PAID} (the lookup found it paid: recorded, not cancelled, E1) · 503
     * {@code PAYMENT_PROVIDER_UNAVAILABLE} (lookup failed: the order stays pending, E2).
     */
    @PostMapping("/{orderCode}/cancel")
    @Operation(summary = "UC-S16 Hủy đơn chờ thanh toán (tra cứu giao dịch trước khi hủy)")
    public OrderDetailResponse cancel(@PathVariable String orderCode, @CurrentUserId Long userId,
                                      @Valid @RequestBody(required = false) CancelOrderRequest request) {
        return orders.cancel(userId, orderCode);
    }
}