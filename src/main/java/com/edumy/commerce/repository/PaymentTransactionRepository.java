package com.edumy.commerce.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.edumy.commerce.entity.HandledAction;
import com.edumy.commerce.entity.PaymentStatus;
import com.edumy.commerce.entity.PaymentTransaction;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** {@code payment_transactions}: QR attempts (UC-S05), payment results (T2) and reconciliation (UC-A14, UC-A18). */
public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, Long> {

    /** UC-S07 / UC-A14: every attempt of an order, newest first (ix_payment_order_created). */
    List<PaymentTransaction> findByOrderIdOrderByCreatedAtDescIdDesc(Long orderId);

    /** UC-S05 {@code GET /orders/{orderCode}/payments/current} (polling): the latest attempt of an order. */
    Optional<PaymentTransaction> findFirstByOrderIdOrderByCreatedAtDescIdDesc(Long orderId);

    /**
     * UC-S05 precondition (reuse a still-valid QR), BR-16: the pending attempt of an order (at most one). When it is
     * closed to create a new QR, flush that change before saving the new attempt (IDENTITY inserts run at persist
     * time and would hit uq_payment_one_pending).
     */
    @Query("""
            select p from PaymentTransaction p
            where p.order.id = :orderId and p.status = com.edumy.commerce.entity.PaymentStatus.PENDING
            """)
    Optional<PaymentTransaction> findPendingByOrderId(@Param("orderId") Long orderId);

    /**
     * The pending attempt of an order (at most one, BR-16) {@code FOR UPDATE}, taken after the order row lock (ADR-011):
     * T1 replacing an order, UC-S16, create QR, schedulers — before the provider lookup.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select p from PaymentTransaction p
            where p.order.id = :orderId and p.status = com.edumy.commerce.entity.PaymentStatus.PENDING
            """)
    Optional<PaymentTransaction> findPendingByOrderIdForUpdate(@Param("orderId") Long orderId);

    /** Reference generation: pre-check ({@code uq_payment_ref} still decides under concurrency). */
    boolean existsByTransactionRef(String transactionRef);

    /**
     * T2 step 1 (ADR-011, R1): order id of the attempt named by a provider result — a scalar, so no
     * {@link PaymentTransaction} enters the persistence context before the order row is locked (an entity loaded
     * before the lock wait would keep its stale fields: {@code FOR UPDATE} on a managed entity does not reload it).
     */
    @Query("select p.order.id from PaymentTransaction p where p.transactionRef = :transactionRef")
    Optional<Long> findOrderIdByTransactionRef(@Param("transactionRef") String transactionRef);

    /** UC-A18 step 1 of the lock order (ADR-011, R1): order id of an attempt, scalar (see above). */
    @Query("select p.order.id from PaymentTransaction p where p.id = :id")
    Optional<Long> findOrderIdById(@Param("id") Long id);

    /**
     * T2 (NFR-04): {@code SELECT … FOR UPDATE} of the attempt named by the provider result, taken AFTER the order row
     * lock (ADR-011 lock order), so a repeated result (UC-S05 E3) waits for the first and then sees its final status.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PaymentTransaction p where p.transactionRef = :transactionRef")
    Optional<PaymentTransaction> findByTransactionRefForUpdate(@Param("transactionRef") String transactionRef);

    /** UC-A18: row lock before resolving, so a transaction is handled at most once (E1). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PaymentTransaction p where p.id = :id")
    Optional<PaymentTransaction> findByIdForUpdate(@Param("id") Long id);

    /**
     * UC-A14 {@code GET /admin/payments?status=}: attempts with their order, newest first; {@code status} null = every
     * status.
     */
    @Query(value = """
            select p.id as id, o.id as orderId, o.orderCode as orderCode, o.studentId as studentId,
                   p.transactionRef as transactionRef, p.amount as amount, p.qrExpiresAt as qrExpiresAt,
                   p.status as status, p.confirmedAt as confirmedAt, p.handledBy as handledBy,
                   p.handledAction as handledAction, p.handledNote as handledNote, p.handledAt as handledAt,
                   p.createdAt as createdAt
            from PaymentTransaction p join p.order o
            where (:status is null or p.status = :status)
            order by p.createdAt desc, p.id desc
            """,
            countQuery = """
            select count(p) from PaymentTransaction p where (:status is null or p.status = :status)
            """)
    Page<AdminPaymentView> searchForAdmin(@Param("status") PaymentStatus status, Pageable pageable);

    /**
     * UC-A18 queue (UC-A14 filter "chờ đối soát chưa xử lý"): needs_review and not handled, oldest first
     * (ix_payment_review_queue). Pass an unsorted {@link Pageable}: the order is fixed here.
     */
    @Query(value = """
            select p.id as id, o.id as orderId, o.orderCode as orderCode, o.studentId as studentId,
                   p.transactionRef as transactionRef, p.amount as amount, p.qrExpiresAt as qrExpiresAt,
                   p.status as status, p.confirmedAt as confirmedAt, p.handledBy as handledBy,
                   p.handledAction as handledAction, p.handledNote as handledNote, p.handledAt as handledAt,
                   p.createdAt as createdAt
            from PaymentTransaction p join p.order o
            where p.status = com.edumy.commerce.entity.PaymentStatus.NEEDS_REVIEW and p.handledAt is null
            order by p.createdAt asc, p.id asc
            """,
            countQuery = """
            select count(p) from PaymentTransaction p
            where p.status = com.edumy.commerce.entity.PaymentStatus.NEEDS_REVIEW and p.handledAt is null
            """)
    Page<AdminPaymentView> findReviewQueue(Pageable pageable);

    /** Row of the admin payment list and the reconciliation queue (UC-A14, UC-A18). */
    interface AdminPaymentView {
        Long getId();

        Long getOrderId();

        String getOrderCode();

        Long getStudentId();

        String getTransactionRef();

        long getAmount();

        Instant getQrExpiresAt();

        PaymentStatus getStatus();

        Instant getConfirmedAt();

        Long getHandledBy();

        HandledAction getHandledAction();

        String getHandledNote();

        Instant getHandledAt();

        Instant getCreatedAt();
    }
}
