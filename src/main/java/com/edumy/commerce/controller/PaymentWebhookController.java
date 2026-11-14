package com.edumy.commerce.controller;

import com.edumy.commerce.dto.response.PaymentWebhookResponse;
import com.edumy.commerce.service.PaymentService;
import com.edumy.shared.web.StubSupport;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Webhook of the fake payment provider (UC-S05 step 5). <b>Dev only</b>: the controller exists only under the profiles
 * {@code dev} and {@code test}; in any other profile the path is a 404. No JWT (the route is public in
 * {@code SecurityConfig}); the caller proves itself with the {@code X-Signature} header, an HMAC over the raw body
 * (checked by {@code integration.WebhookSignatureVerifier} through the service).
 */
@RestController
@RequestMapping("/api/v1/webhooks/payments")
@Tag(name = "commerce")
@Profile({"dev", "test"})
public class PaymentWebhookController {

    private final PaymentService payments;

    public PaymentWebhookController(PaymentService payments) {
        this.payments = payments;
    }

    /**
     * Phase-1 stub, header {@code X-Edumy-Stub: true}: 200 + acknowledgement; no state change. Missing / wrong signature
     * gives 401; the body is the provider's {@code PaymentWebhookRequest}, read raw so the signature covers the exact
     * bytes.
     */
    @PostMapping("/fake")
    @Operation(summary = "UC-S05 Webhook kết quả thanh toán của cổng giả lập (STUB-P1: chỉ xác nhận đã nhận, dev only)",
            description = "Phase-1 stub, dev/test profiles only. Header X-Signature = hex HMAC-SHA256 of the raw body. "
                    + "Acknowledges only (X-Edumy-Stub: true), no state change.")
    public PaymentWebhookResponse fake(@RequestBody byte[] rawBody,
                                       @RequestHeader(name = "X-Signature", required = false)
                                       String signature,
                                       HttpServletResponse response) {
        StubSupport.mark(response);
        // STUB-P1: replace with PaymentService.receiveWebhook (T2 applyResult, enrollment in one transaction) — UC-S05, BR-14, BR-30
        return payments.receiveWebhook(rawBody, signature);
    }
}
