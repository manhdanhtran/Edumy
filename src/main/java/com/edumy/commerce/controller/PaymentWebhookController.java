package com.edumy.commerce.controller;

import java.util.Map;
import java.util.TreeMap;

import com.edumy.commerce.dto.request.PaymentWebhookRequest;
import com.edumy.commerce.dto.response.PaymentWebhookResponse;
import com.edumy.commerce.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Webhook of the fake payment provider (UC-S05 step 5). <b>Dev only</b>: the controller exists only under the profiles
 * {@code dev} and {@code test}; in any other profile the path is a 404 (a real provider gets its own controller under
 * its profile, calling the same service method). No JWT (the route is public in {@code SecurityConfig}); the caller
 * proves itself with the {@code X-Signature} header, an HMAC over the raw body, checked by the payment gateway before
 * the body is parsed.
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
     * 200 + acknowledgement once the result is applied (T2: paid + enrollments, failed, needs_review) or ignored
     * (repeated result, unknown reference). Missing / wrong signature → 401; signed but unreadable / invalid body → 400.
     * The body is read raw so the signature covers the exact bytes.
     */
    @PostMapping("/fake")
    @Operation(summary = "UC-S05 Webhook kết quả thanh toán của cổng giả lập (dev/test)",
            description = "Dev/test profiles only. Header X-Signature = hex HMAC-SHA256 of the raw body. Applies the "
                    + "result in one transaction (NFR-04), idempotent by transactionRef.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = PaymentWebhookRequest.class))))
    public PaymentWebhookResponse fake(@RequestBody byte[] rawBody, @RequestHeader HttpHeaders headers) {
        Map<String, String> single = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.forEach((name, values) -> {
            if (!values.isEmpty()) {
                single.put(name, values.getFirst());
            }
        });
        return payments.receiveWebhook(rawBody, single);
    }
}
