package com.edumy.commerce.repository;

import java.time.Instant;
import java.util.Optional;

import com.edumy.commerce.entity.CancelReason;
import com.edumy.commerce.entity.Order;
import com.edumy.commerce.entity.OrderStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * {@code orders} and, through the {@link Order} aggregate, {@code order_items} (no repository of their own). Student
 * lookups always include the student id (IDOR scoping, NFR-01): another student's order is simply "not found".
 */
public interface OrderRepository extends JpaRepository<Order, Long> {

    /**
     * UC-S06 {@code GET /orders}: orders of a student, newest first (ix_orders_student_created). Pass an unsorted
     * {@link Pageable}: the order is fixed here.
     */
    @Query(value = """
            select o.id as id, o.studentId as studentId, o.orderCode as orderCode, o.totalAmount as totalAmount,
                   o.status as status, o.cancelReason as cancelReason, o.createdAt as createdAt, o.paidAt as paidAt,
                   o.cancelledAt as cancelledAt,
                   (select count(oi) from OrderItem oi where oi.order = o) as itemCount
            from Order o
            where o.studentId = :studentId
            order by o.createdAt desc, o.id desc
            """,
            countQuery = "select count(o) from Order o where o.studentId = :studentId")
    Page<OrderSummaryView> findSummariesByStudentId(@Param("studentId") Long studentId, Pageable pageable);

    /** UC-S07 {@code GET /orders/{orderCode}}: the student's order with its lines in one query. */
    @EntityGraph(attributePaths = "items")
    Optional<Order> findWithItemsByOrderCodeAndStudentId(String orderCode, Long studentId);

    /** Order of the student without lines: payments polling, cancel (UC-S16), create QR (UC-S05). */
    Optional<Order> findByOrderCodeAndStudentId(String orderCode, Long studentId);

    /**
     * UC-S16 / UC-S05 (ADR-011, R1): id of the student's order as a scalar — the order entity is loaded for the first
     * time by {@link #findByIdForUpdate}, under its lock. Another student's code and an unknown one are both empty.
     */
    @Query("select o.id from Order o where o.orderCode = :orderCode and o.studentId = :studentId")
    Optional<Long> findIdByOrderCodeAndStudentId(@Param("orderCode") String orderCode,
                                                 @Param("studentId") Long studentId);

    /**
     * T1 (UC-S04 step 2, D-24): {@code SELECT … FOR UPDATE} of the student's pending order (at most one, BR-29), to
     * replace it after the provider lookup. Flush the cancel before saving the new order: IDENTITY inserts run at
     * persist time, before pending updates, and would hit uq_orders_one_pending.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select o from Order o
            where o.studentId = :studentId and o.status = com.edumy.commerce.entity.OrderStatus.PENDING
            """)
    Optional<Order> findPendingByStudentIdForUpdate(@Param("studentId") Long studentId);

    /** T2 / UC-S16 / UC-A18: row lock of an order before changing its status. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id")
    Optional<Order> findByIdForUpdate(@Param("id") Long id);

    /**
     * Scheduler claim (NFR-09, ADR-011): one pending order whose pending attempt's QR expired at {@code now}, oldest
     * expiry first, locked {@code FOR UPDATE OF o SKIP LOCKED}. The sort uses only columns of {@code ix_payment_pending_expiry}
     * (status, qr_expires_at, implicit id), so MySQL walks the index and stops at the first free row instead of reading,
     * locking and sorting every candidate (review P11-R-01; {@code ClaimQueryPlanIT} checks the plan) — a row another instance (or a
     * student request) holds is skipped, never waited for. Only the {@code orders} row is locked here (the attempt is
     * locked next, after it: lock order). {@code seen} = ids already handled in this run; never empty (sentinel
     * {@code -1}), so no {@code IN ()} is generated.
     */
    @Query(nativeQuery = true, value = """
            SELECT o.id FROM orders o JOIN payment_transactions p ON p.order_id = o.id
            WHERE p.status = 'pending' AND p.qr_expires_at <= :now AND o.status = 'pending' AND o.id NOT IN (:seen)
            ORDER BY p.qr_expires_at, p.id
            LIMIT 1
            FOR UPDATE OF o SKIP LOCKED""")
    Optional<Long> claimOrderWithExpiredQr(@Param("now") Instant now, @Param("seen") java.util.Collection<Long> seen);

    /**
     * Scheduler claim (BR-29, AC-22): one pending order created at or before {@code cutoff} (now − 24 h), oldest first
     * in the order of ix_orders_pending_created (status, created_at, implicit id), {@code FOR UPDATE SKIP LOCKED}; {@code seen} as above (never empty).
     */
    @Query(nativeQuery = true, value = """
            SELECT o.id FROM orders o
            WHERE o.status = 'pending' AND o.created_at <= :cutoff AND o.id NOT IN (:seen)
            ORDER BY o.created_at, o.id
            LIMIT 1
            FOR UPDATE SKIP LOCKED""")
    Optional<Long> claimOverdueOrder(@Param("cutoff") Instant cutoff, @Param("seen") java.util.Collection<Long> seen);

    /** Order-code generation: pre-check (uq_orders_order_code still decides under concurrency). */
    boolean existsByOrderCode(String orderCode);

    /**
     * UC-A13 {@code GET /admin/orders}: newest first; every filter is optional (null = no filter). {@code orderCode}
     * is an exact match (ai_ci), {@code studentId} comes from an email lookup through {@code identity::api},
     * {@code createdFrom} is inclusive and {@code createdTo} exclusive.
     */
    @Query(value = """
            select o.id as id, o.studentId as studentId, o.orderCode as orderCode, o.totalAmount as totalAmount,
                   o.status as status, o.cancelReason as cancelReason, o.createdAt as createdAt, o.paidAt as paidAt,
                   o.cancelledAt as cancelledAt,
                   (select count(oi) from OrderItem oi where oi.order = o) as itemCount
            from Order o
            where (:status is null or o.status = :status)
              and (:orderCode is null or o.orderCode = :orderCode)
              and (:studentId is null or o.studentId = :studentId)
              and (:createdFrom is null or o.createdAt >= :createdFrom)
              and (:createdTo is null or o.createdAt < :createdTo)
            order by o.createdAt desc, o.id desc
            """,
            countQuery = """
            select count(o) from Order o
            where (:status is null or o.status = :status)
              and (:orderCode is null or o.orderCode = :orderCode)
              and (:studentId is null or o.studentId = :studentId)
              and (:createdFrom is null or o.createdAt >= :createdFrom)
              and (:createdTo is null or o.createdAt < :createdTo)
            """)
    Page<OrderSummaryView> searchForAdmin(@Param("status") OrderStatus status, @Param("orderCode") String orderCode,
                                          @Param("studentId") Long studentId,
                                          @Param("createdFrom") Instant createdFrom,
                                          @Param("createdTo") Instant createdTo, Pageable pageable);

    /** Row of the order history (UC-S06) and of the admin order list (UC-A13). */
    interface OrderSummaryView {
        Long getId();

        Long getStudentId();

        String getOrderCode();

        long getTotalAmount();

        OrderStatus getStatus();

        CancelReason getCancelReason();

        Instant getCreatedAt();

        Instant getPaidAt();

        Instant getCancelledAt();

        long getItemCount();
    }
}
