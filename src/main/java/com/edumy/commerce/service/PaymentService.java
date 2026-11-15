package com.edumy.commerce.service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;

import com.edumy.commerce.config.PaymentProperties;
import com.edumy.commerce.dto.response.PaymentQrResponse;
import com.edumy.commerce.dto.response.PaymentWebhookResponse;
import com.edumy.commerce.entity.Order;
import com.edumy.commerce.entity.PaymentStatus;
import com.edumy.commerce.entity.PaymentTransaction;
import com.edumy.commerce.integration.PaymentGateway;
import com.edumy.commerce.integration.PaymentResult;
import com.edumy.commerce.mapper.PaymentMapper;
import com.edumy.commerce.repository.OrderRepository;
import com.edumy.commerce.repository.PaymentTransactionRepository;
import com.edumy.shared.error.BusinessException;
import com.edumy.shared.error.ErrorCode;
import com.edumy.shared.text.LogSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Payment use cases of UC-S05. The provider's result (webhook) is real since P11-a: signature on the raw body, then T2
 * ({@link PaymentResultApplier}) in one transaction. Create QR is still the <b>phase-1 STUB</b> (P11-c replaces it).
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private final OrderRepository orders;
    private final PaymentTransactionRepository payments;
    private final PaymentMapper mapper;
    private final PaymentProperties properties;
    private final PaymentGateway gateway;
    private final PaymentResultApplier applier;
    private final TransactionTemplate tx;
    private final Clock clock;

    public PaymentService(OrderRepository orders, PaymentTransactionRepository payments, PaymentMapper mapper,
                          PaymentProperties properties, PaymentGateway gateway, PaymentResultApplier applier,
                          PlatformTransactionManager transactionManager, Clock clock) {
        this.orders = orders;
        this.payments = payments;
        this.mapper = mapper;
        this.properties = properties;
        this.gateway = gateway;
        this.applier = applier;
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setTimeout(properties.txTimeoutSeconds());
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
     * UC-S05 step 5–7: the provider's notification. {@link PaymentGateway#verifyWebhook} checks the signature over the
     * RAW body before anything is parsed or read from the database (401 / 400, nothing touched); then T2 runs in one
     * transaction (NFR-04): order row locked, attempt locked, {@link PaymentResultApplier}. A reference Edumy does not
     * know is acknowledged and logged (the provider must not retry forever); a repeated result is acknowledged and
     * changes nothing (E3, AC-11).
     *
     * @param headers request headers, keys case-insensitive
     */
    public PaymentWebhookResponse receiveWebhook(byte[] rawBody, Map<String, String> headers) {
        PaymentResult result = gateway.verifyWebhook(rawBody, headers);
        log.info("Payment result received: transactionRef={}, orderCode={}, success={}, amount={}",
                LogSanitizer.clean(result.reference()), LogSanitizer.clean(result.orderCode()), result.success(),
                result.amount());
        tx.executeWithoutResult(status -> applyResult(result));
        return new PaymentWebhookResponse(true, result.reference());
    }

    /**
     * T2 lock order (ADR-011): the order id is read as a scalar (no entity before the wait, R1), the order row is
     * locked, then the attempt is loaded under its own lock.
     */
    private void applyResult(PaymentResult result) {
        Optional<Long> orderId = payments.findOrderIdByTransactionRef(result.reference());
        if (orderId.isEmpty()) {
            log.warn("Payment result for an unknown reference ignored: transactionRef={}",
                    LogSanitizer.clean(result.reference()));
            return;
        }
        Order order = orders.findByIdForUpdate(orderId.get())
                .orElseThrow(() -> new IllegalStateException("order of a payment vanished"));
        PaymentTransaction payment = payments.findByTransactionRefForUpdate(result.reference())
                .orElseThrow(() -> new IllegalStateException("payment vanished under the order lock"));
        applier.apply(order, payment, result);
    }
}
