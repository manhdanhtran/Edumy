package com.edumy.commerce.controller;

import com.edumy.commerce.dto.response.PaymentQrResponse;
import com.edumy.commerce.dto.response.PaymentStatusResponse;
import com.edumy.commerce.service.PaymentQueryService;
import com.edumy.commerce.service.PaymentService;
import com.edumy.shared.security.CurrentUserId;
import com.edumy.shared.web.StubSupport;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
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
     * Phase-1 stub, header {@code X-Edumy-Stub: true}: 201 + a sample pending QR (amount = order total, expires in 15 min,
     * BR-13), or 200 + the stored attempt when the order still has a valid pending QR; nothing is inserted, no provider
     * is called. 404 for an unknown or foreign code · 409 {@code DATA_CONFLICT} when the order is not pending.
     */
    @PostMapping
    @Operation(summary = "UC-S05 Tạo mã QR thanh toán cho đơn chờ (STUB-P1: QR mẫu, không ghi dữ liệu, không gọi cổng thanh toán)",
            description = "Phase-1 stub: returns a sample QR (X-Edumy-Stub: true), nothing is inserted.")
    public ResponseEntity<PaymentQrResponse> createQr(@PathVariable String orderCode, @CurrentUserId Long userId,
                                                      HttpServletResponse response) {
        StubSupport.mark(response);
        // STUB-P1: replace with PaymentService.createQr (gateway QR + insert pending attempt) — UC-S05, BR-13, BR-16
        PaymentService.QrOutcome outcome = payments.createQr(userId, orderCode);
        return ResponseEntity.status(outcome.reused() ? HttpStatus.OK : HttpStatus.CREATED).body(outcome.qr());
    }
}
