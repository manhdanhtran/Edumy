package com.edumy.commerce.entity;

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
 * {@code order_items} (EDU-DDD-001 §5.13): one course of an order with the price locked when the order was created
 * (BR-12). Part of the {@link Order} aggregate (no repository of its own); written once, never changed.
 */
@Entity
@Table(name = "order_items")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false, updatable = false)
    private Order order;

    /** Course (authoring): plain id. */
    @Column(name = "course_id", nullable = false, updatable = false)
    private Long courseId;

    /** VND, &gt; 0 (ck_order_items_price). */
    @Column(name = "unit_price", nullable = false, updatable = false)
    private long unitPrice;

    static OrderItem create(Order order, Long courseId, long unitPrice) {
        OrderItem item = new OrderItem();
        item.order = order;
        item.courseId = courseId;
        item.unitPrice = unitPrice;
        return item;
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
        return id != null && id.equals(((OrderItem) o).getId());
    }

    @Override
    public final int hashCode() {
        return JpaEntities.effectiveClass(this).hashCode();
    }

    @Override
    public String toString() {
        return "OrderItem[id=" + getId() + ", courseId=" + courseId + ", unitPrice=" + unitPrice + "]";
    }
}
