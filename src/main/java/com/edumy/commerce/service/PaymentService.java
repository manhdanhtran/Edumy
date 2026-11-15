package com.edumy.commerce.service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.edumy.commerce.config.PaymentProperties;
import com.edumy.commerce.dto.request.PaymentWebhookRequest;
import com.edumy.commerce.dto.response.PaymentQrResponse;
import com.edumy.commerce.dto.response.PaymentWebhookResponse;
import com.edumy.commerce.entity.Order;
import com.edumy.commerce.entity.PaymentStatus;
import com.edumy.commerce.entity.PaymentTransaction;
import com.edumy.commerce.integration.WebhookSignatureVerifier;
import com.edumy.commerce.mapper.PaymentMapper;
import com.edumy.commerce.repository.OrderRepository;
import com.edumy.commerce.repository.PaymentTransactionRepository;
import com.edumy.shared.error.BusinessException;
import com.edumy.shared.error.ErrorCode;
import com.edumy.shared.error.FieldViolation;
import com.edumy.shared.text.LogSanitizer;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Payment use cases, <b>phase-1 STUBS</b> (ADR-005): UC-S05 create QR and the fake provider's webhook only read and
 * compute; they never write, never call a payment provider. Phase 2 replaces the bodies with the real flow
 * ({@code FakeQrPaymentGateway}, T2 {@code applyResult}, docs/ai/01 §1.11); the controllers stay.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private final OrderRepository orders;
    private final PaymentTransactionRepository payments;
    private final PaymentMapper mapper;
    private final PaymentProperties properties;
    private final WebhookSignatureVerifier verifier;
    private final JsonMapper json;
    private final Validator validator;
    private final Clock clock;

    public PaymentService(OrderRepository orders, PaymentTransactionRepository payments, PaymentMapper mapper,
                          PaymentProperties properties, WebhookSignatureVerifier verifier, JsonMapper json,
                          Validator validator, Clock clock) {
        this.orders = orders;
        this.payments = payments;
        this.mapper = mapper;
        this.properties = properties;
        this.verifier = verifier;
        this.json = json;
        this.validator = validator;
        this.clock = clock;
    }

    /** {@code reused}: a still-valid pending QR of the order is shown again instead of a new one (UC-S05 precondition). */
    public record QrOutcome(PaymentQrResponse qr, boolean reused) {
    }

    /**
     * UC-S05 create QR. The order must be the caller's own (unknown and foreign → the same 404) and {@code pending}
     * (BR-13…16; else 409 {@code DATA_CONFLICT}). Precondition "pending attempt still valid → show that QR": that stored
     * attempt is returned ({@code reused = true}). Otherwise a deterministic sample: reference
     * {@code FAKEPAY-<yyyyMMdd>-STUB}, payload {@code FAKEQR|EDUMY|<orderCode>|<amount>} (the seed format), amount = order
     * total (BR-14), expiry = now + 15 min (BR-13). Nothing is stored.
     */
    @Transactional(readOnly = true)
    public QrOutcome createQr(Long studentId, String orderCode) {
        Order order = orders.findByOrderCodeAndStudentId(orderCode, studentId)
                .orElseThrow(BusinessException::notFound);
        if (!order.isPending()) {
            throw new BusinessException(ErrorCode.DATA_CONFLICT);
        }
        Instant now = clock.instant();
        Optional<PaymentTransaction> valid = payments.findPendingByOrderId(order.getId())
                .filter(p -> !p.isQrExpired(now));
        if (valid.isPresent()) {
            return new QrOutcome(mapper.toQr(order.getOrderCode(), valid.get()), true);
        }
        return new QrOutcome(new PaymentQrResponse(order.getOrderCode(), "FAKEPAY-" + DAY.format(now) + "-STUB",
                PaymentStatus.PENDING.code(), order.getTotalAmount(),
                "FAKEQR|EDUMY|" + order.getOrderCode() + "|" + order.getTotalAmount(),
                now.plus(properties.qrTtl()), now), false);
    }

    /**
     * UC-S05 step 5 webhook of the fake provider. The signature over the raw body is checked first (missing / wrong →
     * 401 {@code UNAUTHENTICATED}, constant time, nothing is logged about it); then the body is parsed and validated like
     * a {@code @Valid @RequestBody} (400 {@code MALFORMED_REQUEST} / {@code VALIDATION_FAILED}). Stub: acknowledges only,
     * T2 (verify reference / order / amount, apply, enroll) is phase 2. No state change, no database access.
     */
    public PaymentWebhookResponse receiveWebhook(byte[] rawBody, String signature) {
        if (!verifier.verify(rawBody, signature)) {
            log.warn("Payment webhook refused: invalid or missing signature");
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }
        PaymentWebhookRequest request;
        try {
            request = json.readValue(rawBody, PaymentWebhookRequest.class);
        } catch (JacksonException e) {
            throw new BusinessException(ErrorCode.MALFORMED_REQUEST);
        }
        if (request == null) {
            throw new BusinessException(ErrorCode.MALFORMED_REQUEST);
        }
        Set<ConstraintViolation<PaymentWebhookRequest>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            List<FieldViolation> errors = violations.stream()
                    .map(v -> new FieldViolation(v.getPropertyPath().toString(), v.getMessage()))
                    .sorted(Comparator.comparing(FieldViolation::field))
                    .toList();
            throw BusinessException.withErrors(ErrorCode.VALIDATION_FAILED, errors);
        }
        log.info("Payment webhook received (stub, ignored): transactionRef={}, orderCode={}, status={}",
                LogSanitizer.clean(request.transactionRef()), LogSanitizer.clean(request.orderCode()),
                request.status());
        return new PaymentWebhookResponse(true, request.transactionRef());
    }
}
