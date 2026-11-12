package com.edumy.commerce.entity;

import java.time.Instant;

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
 * {@code cart_items} (EDU-DDD-001 §5.11): one course in a cart, at most once (BR-11, uq_cart_items_cart_course).
 * Created and removed only through {@link Cart}. The item has no price: the cart shows the current price (UC-S02), the
 * price is locked only in {@link OrderItem} (BR-12).
 */
@Entity
@Table(name = "cart_items")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CartItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cart_id", nullable = false, updatable = false)
    private Cart cart;

    /** Course (authoring): plain id. */
    @Column(name = "course_id", nullable = false, updatable = false)
    private Long courseId;

    @Column(name = "added_at", nullable = false, updatable = false)
    private Instant addedAt;

    static CartItem create(Cart cart, Long courseId, Instant now) {
        CartItem item = new CartItem();
        item.cart = cart;
        item.courseId = courseId;
        item.addedAt = now;
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
        return id != null && id.equals(((CartItem) o).getId());
    }

    @Override
    public final int hashCode() {
        return JpaEntities.effectiveClass(this).hashCode();
    }

    @Override
    public String toString() {
        return "CartItem[id=" + getId() + ", courseId=" + courseId + "]";
    }
}
