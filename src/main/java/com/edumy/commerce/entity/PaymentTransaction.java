package com.edumy.commerce.entity;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import com.edumy.shared.error.BusinessException;
import com.edumy.shared.error.ErrorCode;
import com.edumy.shared.error.FieldViolation;
import com.edumy.shared.jpa.JpaEntities;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * {@code payment_transactions} (EDU-DDD-001 §5.14): one QR attempt of an order (BR-23), at most one pending per order
 * (BR-16, uq_payment_one_pending on the unmapped generated column {@code pending_order_id}, docs/ai/03 §3.3),
 * {@code transaction_ref} unique (NFR-04 idempotency key).
 * <p>State machine (docs/ai/02 §2.2, SRS §3.4.3): new → pending · pending → success|failed|expired ·
 * pending|expired|failed → success (late valid result, BR-30) · pending|expired|failed → needs_review ·
 * needs_review → success (admin activated) · needs_review stays needs_review once handled (admin refund_outside).
 * A transaction is handled at most once (UC-A18 E1). Other transitions → {@code DATA_CONFLICT}.
 * <p>Invariants mirrored from V3: success ⇒ confirmed_at (ck_payment_confirmed); handled_* all set or all empty
 * (ck_payment_handling_complete) and only on needs_review|success (ck_payment_handling_status); QR expiry after
 * creation (ck_payment_qr_expiry). Verifying the provider result (reference, order, amount, BR-14) and the ownership
 * conflict of BR-30 are T2 (service) concerns.
 */
@Entity
@Table(name = "payment_transactions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentTransaction {

    static final int TRANSACTION_REF_MAX_LENGTH = 100;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false, updatable = false)
    private Order order;

    @Column(name = "transaction_ref", nullable = false, length = TRANSACTION_REF_MAX_LENGTH, updatable = false)
    private String transactionRef;

    /** VND expected by the QR = the order total at creation (UC-S05 step 1, BR-14). */
    @Column(name = "amount", nullable = false, updatable = false)
    private long amount;

    @Column(name = "qr_data", nullable = false, columnDefinition = "TEXT", updatable = false)
    private String qrData;

    /** 15 minutes after creation (BR-13); the duration is configuration, passed in by the service. */
    @Column(name = "qr_expires_at", nullable = false, updatable = false)
    private Instant qrExpiresAt;

    @Column(name = "status", nullable = false, length = 20)
    private PaymentStatus status;

    /** Time the payment was recognised as successful (provider result or admin activation). */
    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    /** Admin who resolved the needs_review transaction (identity): plain id. */
    @Column(name = "handled_by")
    private Long handledBy;

    @Column(name = "handled_action", length = 20)
    private HandledAction handledAction;

    @Column(name = "handled_note", columnDefinition = "TEXT")
    private String handledNote;

    @Column(name = "handled_at")
    private Instant handledAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Maintained by MySQL (ON UPDATE CURRENT_TIMESTAMP(6)); null on a freshly persisted instance until reloaded. */
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    /**
     * UC-S05 steps 1–2: pending QR attempt for a pending {@code order} (another status → {@code DATA_CONFLICT}).
     * {@code amount} must equal the order total (BR-14) and {@code qrExpiresAt} must be after {@code now}; reference,
     * QR data and expiry come from the payment gateway / configuration, so a violation is a programming error
     * ({@link IllegalArgumentException}). The caller first closes or reuses an existing pending attempt (BR-16).
     */
    public static PaymentTransaction create(Order order, String transactionRef, long amount, String qrData,
                                            Instant qrExpiresAt, Instant now) {
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(qrExpiresAt, "qrExpiresAt");
        Objects.requireNonNull(now, "now");
        if (transactionRef == null || transactionRef.isBlank()
                || transactionRef.length() > TRANSACTION_REF_MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "transaction ref must be 1.." + TRANSACTION_REF_MAX_LENGTH + " characters");
        }
        if (qrData == null || qrData.isBlank()) {
            throw new IllegalArgumentException("QR data must not be blank");
        }
        if (amount <= 0 || amount != order.getTotalAmount()) {
            throw new IllegalArgumentException("amount must equal the order total (BR-14)");
        }
        if (!qrExpiresAt.isAfter(now)) {
            throw new IllegalArgumentException("QR expiry must be after creation (ck_payment_qr_expiry)");
        }
        if (!order.isPending()) {
            throw new BusinessException(ErrorCode.DATA_CONFLICT);
        }
        PaymentTransaction payment = new PaymentTransaction();
        payment.order = order;
        payment.transactionRef = transactionRef;
        payment.amount = amount;
        payment.qrData = qrData;
        payment.qrExpiresAt = qrExpiresAt;
        payment.status = PaymentStatus.PENDING;
        payment.createdAt = now;
        return payment;
    }

    /** UC-S05 step 7: valid result, also late (BR-30): pending|expired|failed → success. */
    public void markSuccess(Instant now) {
        Objects.requireNonNull(now, "now");
        requireStatusIn(PaymentStatus.PENDING, PaymentStatus.EXPIRED, PaymentStatus.FAILED);
        status = PaymentStatus.SUCCESS;
        confirmedAt = now;
    }

    /** UC-S05 A2: failed result, pending → failed. */
    public void markFailed(Instant now) {
        Objects.requireNonNull(now, "now");
        requireStatusIn(PaymentStatus.PENDING);
        status = PaymentStatus.FAILED;
    }

    /**
     * UC-S05 A1 (QR timer) / UC-S16 step 2 and UC-S04 step 2 (order cancelled or replaced, so possibly before
     * {@link #qrExpiresAt}): pending → expired, after the provider lookup said "not paid" (service).
     */
    public void expire(Instant now) {
        Objects.requireNonNull(now, "now");
        requireStatusIn(PaymentStatus.PENDING);
        status = PaymentStatus.EXPIRED;
    }

    /** UC-S05 E2 / A3: amount or order mismatch, ownership conflict: pending|expired|failed → needs_review. */
    public void flagNeedsReview(Instant now) {
        Objects.requireNonNull(now, "now");
        requireStatusIn(PaymentStatus.PENDING, PaymentStatus.EXPIRED, PaymentStatus.FAILED);
        status = PaymentStatus.NEEDS_REVIEW;
    }

    /**
     * UC-A18 (a): unhandled needs_review → success, handled by {@code adminId} with a required note (D-29). The
     * service marks the order paid and grants the enrollments in the same transaction.
     */
    public void activate(Long adminId, String note, Instant now) {
        handle(adminId, note, HandledAction.ACTIVATED, now);
        status = PaymentStatus.SUCCESS;
        confirmedAt = now;
    }

    /** UC-A18 (b): unhandled needs_review stays needs_review, handled as refunded outside the system (D-29). */
    public void refundOutside(Long adminId, String note, Instant now) {
        handle(adminId, note, HandledAction.REFUND_OUTSIDE, now);
    }

    public boolean isPending() {
        return status == PaymentStatus.PENDING;
    }

    public boolean isHandled() {
        return handledAt != null;
    }

    /** UC-A18 queue: needs_review and not handled yet (ix_payment_review_queue). */
    public boolean isAwaitingReview() {
        return status == PaymentStatus.NEEDS_REVIEW && !isHandled();
    }

    /** QR validity (BR-13): expired at and after {@link #qrExpiresAt}. */
    public boolean isQrExpired(Instant now) {
        return !qrExpiresAt.isAfter(now);
    }

    private void handle(Long adminId, String note, HandledAction action, Instant now) {
        Objects.requireNonNull(adminId, "adminId");
        Objects.requireNonNull(now, "now");
        String validNote = requireNote(note);
        if (!isAwaitingReview()) {
            throw new BusinessException(ErrorCode.DATA_CONFLICT);
        }
        handledBy = adminId;
        handledAction = action;
        handledNote = validNote;
        handledAt = now;
    }

    private void requireStatusIn(PaymentStatus... allowed) {
        for (PaymentStatus candidate : allowed) {
            if (status == candidate) {
                return;
            }
        }
        throw new BusinessException(ErrorCode.DATA_CONFLICT);
    }

    /** UC-A18 E3: trimmed, non-blank note; otherwise {@code VALIDATION_FAILED} on {@code note}. */
    private static String requireNote(String note) {
        String trimmed = note == null ? "" : note.strip();
        if (trimmed.isEmpty()) {
            throw BusinessException.withErrors(ErrorCode.VALIDATION_FAILED,
                    List.of(new FieldViolation("note", "Ghi chú đối soát không được để trống")));
        }
        return trimmed;
    }

    @Override
    public final boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || JpaEntities.effectiveClass(this) != JpaEntities.effectiveClass(o)) {
            return false;
        }
        Long id = getId();
        return id != null && id.equals(((PaymentTransaction) o).getId());
    }

    @Override
    public final int hashCode() {
        return JpaEntities.effectiveClass(this).hashCode();
    }

    @Override
    public String toString() {
        return "PaymentTransaction[id=" + getId() + ", transactionRef=" + transactionRef + ", status=" + status + "]";
    }
}
