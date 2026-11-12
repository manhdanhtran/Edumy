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
