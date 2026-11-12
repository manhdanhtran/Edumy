package com.edumy.commerce.entity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.edumy.shared.error.BusinessException;
import com.edumy.shared.error.ErrorCode;
import com.edumy.shared.jpa.JpaEntities;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * {@code orders} (EDU-DDD-001 §5.12). Aggregate root of its {@link OrderItem}s, which are written once with the order
 * ({@code cascade = PERSIST}) and never changed or deleted (BR-12 price snapshot, BR-28).
 * <p>State machine (docs/ai/02 §2.2, SRS §3.4.2): new → pending · pending → paid · pending → cancelled
 * (user | replaced | expired) · cancelled → paid (late valid result or admin activation, BR-30). A late-paid order
 * keeps {@code cancelled_at} / {@code cancel_reason} (ck_orders_state). Paid is final. Other transitions →
 * {@code DATA_CONFLICT}.
 * <p>The generated column {@code pending_student_id} (uq_orders_one_pending, BR-29, docs/ai/03 §3.3) is not mapped.
 * Who may act, the provider lookup before cancelling (D-24, UC-S16) and the ownership checks of BR-30 are service
 * concerns.
 */
@Entity
@Table(name = "orders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order {

    static final int ORDER_CODE_MAX_LENGTH = 30;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Buyer (identity): plain id, never changes. */
    @Column(name = "student_id", nullable = false, updatable = false)
    private Long studentId;

    @Column(name = "order_code", nullable = false, length = ORDER_CODE_MAX_LENGTH, updatable = false)
    private String orderCode;

    /** VND, Σ unit_price of the lines, fixed at creation (BR-27, D-25). */
    @Column(name = "total_amount", nullable = false, updatable = false)
    private long totalAmount;

    @Column(name = "status", nullable = false, length = 20)
    private OrderStatus status;

    @Column(name = "cancel_reason", length = 20)
    private CancelReason cancelReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    /** Maintained by MySQL (ON UPDATE CURRENT_TIMESTAMP(6)); null on a freshly persisted instance until reloaded. */
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.PERSIST)
    @OrderBy("id ASC")
    private List<OrderItem> items = new ArrayList<>();

    /** One order line to create: the course and its price locked now (BR-12). */
    public record Line(Long courseId, long unitPrice) {

        public Line {
            Objects.requireNonNull(courseId, "courseId");
        }
    }

    /**
     * UC-S04 step 5 (T1): new pending order of {@code studentId} with one line per course. The lines come from the
     * re-validated cart (D-26), so an empty list, a repeated course or a price ≤ 0 is a programming error
     * ({@link IllegalArgumentException}); "no valid item left" (UC-S04 E1) must be answered by the service before.
     * {@code total_amount} = Σ unit_price (BR-27).
     */
    public static Order create(Long studentId, String orderCode, List<Line> lines, Instant now) {
        Objects.requireNonNull(studentId, "studentId");
        Objects.requireNonNull(lines, "lines");
        Objects.requireNonNull(now, "now");
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("an order needs at least one line (BR-27)");
        }
        Order order = new Order();
        order.studentId = studentId;
        order.orderCode = requireOrderCode(orderCode);
        order.status = OrderStatus.PENDING;
        order.createdAt = now;
        Set<Long> courses = new HashSet<>();
        long total = 0;
        for (Line line : lines) {
            if (!courses.add(line.courseId())) {
                throw new IllegalArgumentException("course " + line.courseId() + " appears twice in the order");
            }
            if (line.unitPrice() <= 0) {
                throw new IllegalArgumentException("unit price must be > 0 (ck_order_items_price)");
            }
            order.items.add(OrderItem.create(order, line.courseId(), line.unitPrice()));
            total = Math.addExact(total, line.unitPrice());
        }
        order.totalAmount = total;
        return order;
    }

    /**
     * UC-S05 step 7 / UC-A18 (a): pending|cancelled → paid. From cancelled (late valid result, admin activation,
     * BR-30) the cancel data is kept as history. Paid → {@code DATA_CONFLICT}.
     */
    public void markPaid(Instant now) {
        Objects.requireNonNull(now, "now");
        if (status != OrderStatus.PENDING && status != OrderStatus.CANCELLED) {
            throw new BusinessException(ErrorCode.DATA_CONFLICT);
        }
        status = OrderStatus.PAID;
        paidAt = now;
    }

    /**
     * UC-S16 (user, expired) / UC-S04 step 2 (replaced): pending → cancelled, only after the provider lookup said
     * "not paid" (service).
     */
    public void cancel(CancelReason reason, Instant now) {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(now, "now");
        if (status != OrderStatus.PENDING) {
            throw new BusinessException(ErrorCode.DATA_CONFLICT);
        }
        status = OrderStatus.CANCELLED;
        cancelReason = reason;
        cancelledAt = now;
    }

    public boolean isPending() {
        return status == OrderStatus.PENDING;
    }

    public boolean isPaid() {
        return status == OrderStatus.PAID;
    }

    public boolean isOwnedBy(Long userId) {
        return studentId.equals(userId);
    }

    public List<Long> courseIds() {
        return items.stream().map(OrderItem::getCourseId).toList();
    }

    /** Read-only view: lines are fixed at creation. */
    public List<OrderItem> getItems() {
        return Collections.unmodifiableList(items);
    }

    private static String requireOrderCode(String orderCode) {
        if (orderCode == null || orderCode.isBlank() || orderCode.length() > ORDER_CODE_MAX_LENGTH) {
            throw new IllegalArgumentException("order code must be 1.." + ORDER_CODE_MAX_LENGTH + " characters");
        }
        return orderCode;
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
        return id != null && id.equals(((Order) o).getId());
    }

    @Override
    public final int hashCode() {
        return JpaEntities.effectiveClass(this).hashCode();
    }

    @Override
    public String toString() {
        return "Order[id=" + getId() + ", orderCode=" + orderCode + ", status=" + status + "]";
    }
}
