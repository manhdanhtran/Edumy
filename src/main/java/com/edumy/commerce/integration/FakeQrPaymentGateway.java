package com.edumy.commerce.integration;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.edumy.shared.error.BusinessException;
import com.edumy.shared.error.ErrorCode;
import com.edumy.shared.error.FieldViolation;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * In-process payment provider for dev and tests (every profile but {@code payos}). It never calls the network.
 * <ul>
 *   <li>{@link #createQr}: payload {@code FAKEQR|EDUMY|<reference>|<amount>} — the reference is the transaction
 *   reference, not the order code (D-27).</li>
 *   <li>{@link #verifyWebhook}: HMAC-SHA256 of the raw body ({@link WebhookSignatureVerifier}, header
 *   {@code X-Signature}) BEFORE parsing; a verified notification is also remembered as the provider's state of that
 *   reference, so a later {@link #query} answers it (the fake "knows what it sent").</li>
 *   <li>{@link #query}: the remembered state, {@link PaymentLookup.NotPaid} by default.</li>
 * </ul>
 * Test hooks ({@code simulate*}, {@link #setUnavailable}, {@link #queryCount}, {@link #reset}) script the provider; the
 * state is in memory, per instance (fine for dev and tests; never used in production).
 */
@Component
@Profile("!payos")
public class FakeQrPaymentGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(FakeQrPaymentGateway.class);

    private final WebhookSignatureVerifier verifier;
    private final JsonMapper json;
    private final Validator validator;
    private final Clock clock;
    private final Map<String, PaymentLookup> outcomes = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> queries = new ConcurrentHashMap<>();
    private volatile boolean unavailable;

    public FakeQrPaymentGateway(WebhookSignatureVerifier verifier, JsonMapper json, Validator validator, Clock clock) {
        this.verifier = verifier;
        this.json = json;
        this.validator = validator;
        this.clock = clock;
    }

    @Override
    public QrCode createQr(String reference, long amount, Instant expiresAt) {
        if (unavailable) {
            throw new PaymentGatewayException("fake provider unavailable (createQr)");
        }
        return new QrCode("FAKEQR|EDUMY|" + reference + "|" + amount);
    }

    @Override
    public PaymentLookup query(String reference) {
        queries.computeIfAbsent(reference, r -> new AtomicInteger()).incrementAndGet();
        if (unavailable) {
            throw new PaymentGatewayException("fake provider unavailable (query)");
        }
        return outcomes.getOrDefault(reference, new PaymentLookup.NotPaid());
    }

    @Override
    public PaymentResult verifyWebhook(byte[] rawBody, Map<String, String> headers) {
        if (!verifier.verify(rawBody, headers.get(WebhookSignatureVerifier.HEADER))) {
            log.warn("Payment webhook refused: invalid or missing signature");
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }
        FakePaymentNotification notification;
        try {
            notification = json.readValue(rawBody, FakePaymentNotification.class);
        } catch (JacksonException e) {
            throw new BusinessException(ErrorCode.MALFORMED_REQUEST);
        }
        if (notification == null) {
            throw new BusinessException(ErrorCode.MALFORMED_REQUEST);
        }
        Set<ConstraintViolation<FakePaymentNotification>> violations = validator.validate(notification);
        if (!violations.isEmpty()) {
            List<FieldViolation> errors = violations.stream()
                    .map(v -> new FieldViolation(v.getPropertyPath().toString(), v.getMessage()))
                    .sorted(Comparator.comparing(FieldViolation::field))
                    .toList();
            throw BusinessException.withErrors(ErrorCode.VALIDATION_FAILED, errors);
        }
        boolean success = "success".equals(notification.status());
        Instant paidAt = notification.paidAt() != null ? notification.paidAt() : clock.instant();
        outcomes.put(notification.transactionRef(), success
                ? new PaymentLookup.Paid(notification.amount(), paidAt)
                : new PaymentLookup.Failed());
        return new PaymentResult(notification.transactionRef(), notification.orderCode(), notification.amount(),
                success, success ? paidAt : null);
    }

    // ---- test / dev hooks

    /** The provider will report {@code reference} as paid with {@code amount}. */
    public void simulatePaid(String reference, long amount) {
        outcomes.put(reference, new PaymentLookup.Paid(amount, clock.instant()));
    }

    /** The provider will report {@code reference} as failed. */
    public void simulateFailed(String reference) {
        outcomes.put(reference, new PaymentLookup.Failed());
    }

    /** Every call ({@link #createQr}, {@link #query}) throws {@link PaymentGatewayException} while {@code true}. */
    public void setUnavailable(boolean unavailable) {
        this.unavailable = unavailable;
    }

    /** Number of {@link #query} calls for {@code reference} since the last {@link #reset}. */
    public int queryCount(String reference) {
        AtomicInteger count = queries.get(reference);
        return count == null ? 0 : count.get();
    }

    /** Total number of {@link #query} calls since the last {@link #reset}. */
    public int totalQueries() {
        return queries.values().stream().mapToInt(AtomicInteger::get).sum();
    }

    public void reset() {
        outcomes.clear();
        queries.clear();
        unavailable = false;
    }
}
