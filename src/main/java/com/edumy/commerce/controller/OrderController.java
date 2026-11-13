package com.edumy.commerce.controller;

import com.edumy.commerce.dto.response.OrderDetailResponse;
import com.edumy.commerce.dto.response.OrderSummaryResponse;
import com.edumy.commerce.service.OrderQueryService;
import com.edumy.shared.security.CurrentUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PagedModel;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The caller's orders (UC-S04, S06, S07, S16). Scoped by the caller's id; another student's order is a 404. */
@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "commerce")
public class OrderController {

    private final OrderQueryService queries;

    public OrderController(OrderQueryService queries) {
        this.queries = queries;
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
}
