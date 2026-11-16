package com.edumy.commerce.controller;

import com.edumy.commerce.dto.response.PaymentQrResponse;
import com.edumy.commerce.dto.response.PaymentStatusResponse;
import com.edumy.commerce.service.PaymentQueryService;
import com.edumy.commerce.service.PaymentService;
import com.edumy.shared.security.CurrentUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Payment of the caller's own order (UC-S05). Another student's order is a 404. */
@RestController
@RequestMapping("/api/v1/orders/{orderCode}/payments")
@Tag(name = "commerce")
public class PaymentController {

    private final PaymentQueryService queries;
    private final PaymentService payments;

    public PaymentController(PaymentQueryService queries, PaymentService payments) {
        this.queries = queries;
        this.payments = payments;
    }

    @GetMapping("/current")
    @Operation(summary = "UC-S05 Xem trạng thái thanh toán hiện tại của đơn (polling)")
    public PaymentStatusResponse current(@PathVariable String orderCode, @CurrentUserId Long userId) {
        return queries.current(userId, orderCode);
    }

    /**
     * UC-S05 steps 1–2 (P11-c): 201 + a new pending QR attempt (reference = transaction reference, amount = order total,
     * valid 15 min, BR-13 / BR-14), or 200 + the still-valid pending attempt (precondition). 404 unknown / foreign code ·
     * 409 {@code DATA_CONFLICT} order not pending · 409 {@code ORDER_EXPIRED} (older than 24 h: cancelled now) · 409
     * {@code ORDER_CART_CHANGED} (the order holds a course owned now: cancelled, order again from the cart) · 409
     * {@code ORDER_ALREADY_PAID} (the previous attempt was found paid: recorded) · 503
     * {@code PAYMENT_PROVIDER_UNAVAILABLE} (no QR, nothing changed, E1).
     */
    @PostMapping
    @Operation(summary = "UC-S05 Tạo mã QR thanh toán cho đơn chờ (hoặc hiển thị lại QR còn hạn)")
    public ResponseEntity<PaymentQrResponse> createQr(@PathVariable String orderCode, @CurrentUserId Long userId) {
        PaymentService.QrOutcome outcome = payments.createQr(userId, orderCode);
        return ResponseEntity.status(outcome.reused() ? HttpStatus.OK : HttpStatus.CREATED).body(outcome.qr());
    }
}