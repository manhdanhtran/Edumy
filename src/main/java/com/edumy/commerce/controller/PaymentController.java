package com.edumy.commerce.controller;

import com.edumy.commerce.dto.response.PaymentStatusResponse;
import com.edumy.commerce.service.PaymentQueryService;
import com.edumy.shared.security.CurrentUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Payment of the caller's own order (UC-S05). Another student's order is a 404. */
@RestController
@RequestMapping("/api/v1/orders/{orderCode}/payments")
@Tag(name = "commerce")
public class PaymentController {

    private final PaymentQueryService queries;

    public PaymentController(PaymentQueryService queries) {
        this.queries = queries;
    }

    @GetMapping("/current")
    @Operation(summary = "UC-S05 Xem trạng thái thanh toán hiện tại của đơn (polling)")
    public PaymentStatusResponse current(@PathVariable String orderCode, @CurrentUserId Long userId) {
        return queries.current(userId, orderCode);
    }
}
