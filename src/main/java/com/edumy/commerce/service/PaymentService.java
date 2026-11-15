package com.edumy.commerce.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.edumy.commerce.config.PaymentProperties;
import com.edumy.commerce.dto.response.PaymentQrResponse;
import com.edumy.commerce.dto.response.PaymentWebhookResponse;
import com.edumy.commerce.entity.CancelReason;
import com.edumy.commerce.entity.Order;
import com.edumy.commerce.entity.PaymentTransaction;
import com.edumy.commerce.integration.PaymentGateway;
import com.edumy.commerce.integration.PaymentGatewayException;
import com.edumy.commerce.integration.PaymentResult;
import com.edumy.commerce.integration.QrCode;
import com.edumy.commerce.mapper.PaymentMapper;
import com.edumy.commerce.repository.OrderRepository;
import com.edumy.commerce.repository.PaymentTransactionRepository;
import com.edumy.commerce.service.PendingPaymentSettler.Settlement;
import com.edumy.learning.api.EnrollmentApi;
import com.edumy.shared.error.BusinessException;
import com.edumy.shared.error.ErrorCode;
import com.edumy.shared.text.LogSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Payment use cases of UC-S05 (P11, ADR-011): create a QR for a pending order (P11-c) and the provider's result
 * (webhook, P11-a: signature on the raw body, then T2 {@link PaymentResultApplier} in one transaction).
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private static final int REFERENCE_ATTEMPTS = 5;

    private final OrderRepository orders;
    private final PaymentTransactionRepository payments;
    private final PaymentMapper mapper;
    private final PaymentProperties properties;
    private final PaymentGateway gateway;
    private final PaymentResultApplier applier;
    private final PendingPaymentSettler settler;
    private final EnrollmentApi enrollments;
    private final CommerceCodeGenerator codes;
    private final TransactionTemplate tx;
    private final Clock clock;

    public PaymentService(OrderRepository orders, PaymentTransactionRepository payments, PaymentMapper mapper,
                          PaymentProperties properties, PaymentGateway gateway, PaymentResultApplier applier,
                          PendingPaymentSettler settler, EnrollmentApi enrollments, CommerceCodeGenerator codes,
                          PlatformTransactionManager transactionManager, Clock clock) {
        this.orders = orders;
        this.payments = payments;
        this.mapper = mapper;
        this.properties = properties;
        this.gateway = gateway;
        this.applier = applier;
        this.settler = settler;
        this.enrollments = enrollments;
        this.codes = codes;
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setTimeout(properties.txTimeoutSeconds());
        this.clock = clock;
    }

    /** {@code reused}: a still-valid pending QR of the order is shown again instead of a new one (UC-S05 precondition). */
    public record QrOutcome(PaymentQrResponse qr, boolean reused) {
    }

    /** Result of the QR transaction; a non-null {@code error} is thrown after the commit (ADR-011). */
    private record QrStep(QrOutcome outcome, ErrorCode error) {
    }

    /**
     * UC-S05 steps 1–2 (create a QR attempt for the student's pending order). Order id read as a scalar, order locked
     * (R1); unknown / foreign → 404 identical; not pending → 409 {@code DATA_CONFLICT}. Then, before any QR (P11 R3):
     * <ol>
     *   <li>the order is older than {@code edumy.payment.order-ttl} (24 h, BR-29) → its pending attempt is looked up
     *   and the order cancelled as {@code expired}, committed, 409 {@code DATA_CONFLICT}; found paid → 409
     *   {@code ORDER_ALREADY_PAID};</li>
     *   <li>a course of the order is owned now → looked up and cancelled as {@code replaced}, committed, 409
     *   {@code ORDER_CART_CHANGED} (order again from the cart); found paid → 409 {@code ORDER_ALREADY_PAID};</li>
     *   <li>a pending attempt still valid → shown again, 200 (precondition, BR-16); a pending attempt whose QR expired →
     *   looked up first ("sau khi trạng thái lần trước đã được xác minh", BR-16): paid → 409
     *   {@code ORDER_ALREADY_PAID}, otherwise closed (failed / expired) and a new one is made (A1, AC-10);</li>
     *   <li>new attempt: reference = new transaction reference (D-27), {@code gateway.createQr(reference, total, now +
     *   15 min)} (BR-13, BR-14), stored pending → 201. Provider down (here or in a lookup) → 503
     *   {@code PAYMENT_PROVIDER_UNAVAILABLE}, nothing changed: the order stays pending, the cart as it was (E1).</li>
     * </ol>
     */
    public QrOutcome createQr(Long studentId, String orderCode) {
        QrStep step = tx.execute(status -> createQrLocked(studentId, orderCode));
        if (step.error() != null) {
            throw new BusinessException(step.error()); // the transaction committed first
        }
        return step.outcome();
    }

    private QrStep createQrLocked(Long studentId, String orderCode) {
        Long orderId = orders.findIdByOrderCodeAndStudentId(orderCode, studentId)
                .orElseThrow(BusinessException::notFound);
        Order order = orders.findByIdForUpdate(orderId).orElseThrow(BusinessException::notFound);
        if (!order.isPending()) {
            throw new BusinessException(ErrorCode.DATA_CONFLICT);
        }
        Instant now = clock.instant();
        if (!order.getCreatedAt().plus(properties.orderTtl()).isAfter(now)) { // R3 a, BR-29
            if (settle(order) == Settlement.PAID) {
                return new QrStep(null, ErrorCode.ORDER_ALREADY_PAID);
            }
            order.cancel(CancelReason.EXPIRED, now);
            log.info("Order cancelled as expired before a new QR: orderCode={}", order.getOrderCode());
            return new QrStep(null, ErrorCode.DATA_CONFLICT);
        }
        Set<Long> owned = enrollments.findOwnedCourseIds(order.getStudentId(), order.courseIds());
        if (!owned.isEmpty()) { // R3 b
            if (settle(order) == Settlement.PAID) {
                return new QrStep(null, ErrorCode.ORDER_ALREADY_PAID);
            }
            order.cancel(CancelReason.REPLACED, now);
            log.info("Order cancelled before a new QR, it holds owned courses: orderCode={}, ownedCourseIds={}",
                    order.getOrderCode(), owned);
            return new QrStep(null, ErrorCode.ORDER_CART_CHANGED);
        }
        Optional<PaymentTransaction> pending = payments.findPendingByOrderIdForUpdate(order.getId());
        if (pending.isPresent() && !pending.get().isQrExpired(now)) {
            return new QrStep(new QrOutcome(mapper.toQr(order.getOrderCode(), pending.get()), true), null);
        }
        if (pending.isPresent()) {
            Settlement settlement = settle(order);
            if (settlement == Settlement.PAID) {
                return new QrStep(null, ErrorCode.ORDER_ALREADY_PAID);
            }
            payments.flush(); // the old attempt is no longer pending before the insert (uq_payment_one_pending)
        }
        String reference = newReference();
        Instant expiresAt = now.plus(properties.qrTtl());
        QrCode qr = createQr(reference, order, expiresAt);
        PaymentTransaction payment = PaymentTransaction.create(order, reference, order.getTotalAmount(), qr.qrData(),
                expiresAt, now);
        payments.saveAndFlush(payment);
        log.info("QR created: transactionRef={}, orderCode={}, amount={}, expiresAt={}", reference,
                order.getOrderCode(), order.getTotalAmount(), expiresAt);
        return new QrStep(new QrOutcome(mapper.toQr(order.getOrderCode(), payment), false), null);
    }

    private QrCode createQr(String reference, Order order, Instant expiresAt) {
        try {
            return gateway.createQr(reference, order.getTotalAmount(), expiresAt);
        } catch (PaymentGatewayException e) {
            log.warn("QR not created, payment provider unavailable: orderCode={}, error={}", order.getOrderCode(),
                    e.getMessage());
            throw new BusinessException(ErrorCode.PAYMENT_PROVIDER_UNAVAILABLE); // UC-S05 E1: rollback
        }
    }

    private Settlement settle(Order order) {
        try {
            return settler.settle(order);
        } catch (PaymentGatewayException e) {
            log.warn("Payment provider unavailable: orderCode={}, error={}", order.getOrderCode(), e.getMessage());
            throw new BusinessException(ErrorCode.PAYMENT_PROVIDER_UNAVAILABLE);
        }
    }

    private String newReference() {
        for (int i = 0; i < REFERENCE_ATTEMPTS; i++) {
            String reference = codes.newTransactionRef();
            if (!payments.existsByTransactionRef(reference)) {
                return reference;
            }
        }
        throw new BusinessException(ErrorCode.DATA_CONFLICT);
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
