package com.edumy.commerce.entity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
 * {@code carts} (EDU-DDD-001 §5.10): at most one cart per account (BR-22, uq_carts_student), created with the first
 * item. The cart is the aggregate root of its {@link CartItem}s: items are added and removed only through this class
 * ({@code cascade = ALL}, {@code orphanRemoval}), so the duplicate check of BR-11 sees every item.
 * <p>BR-10 (course already owned), BR-32 (own course), BR-09 (free course) and BR-31 (only published courses are sold)
 * need other modules and are checked by the service before {@link #addItem}.
 * <p>Hibernate flushes inserts before orphan deletes: removing and re-adding the same course in one transaction would
 * hit uq_cart_items_cart_course, so do one or the other per request.
 */
@Entity
@Table(name = "carts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Cart {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Owner (identity): plain id, never changes. */
    @Column(name = "student_id", nullable = false, updatable = false)
    private Long studentId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Maintained by MySQL (ON UPDATE CURRENT_TIMESTAMP(6)); null on a freshly persisted instance until reloaded. */
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    /** UC-S02 shows the items in the order they were added. */
    @OneToMany(mappedBy = "cart", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("addedAt ASC, id ASC")
    private List<CartItem> items = new ArrayList<>();

    /** UC-S01 step 2: empty cart of {@code studentId} (the service creates it when the first item is added). */
    public static Cart create(Long studentId, Instant now) {
        Cart cart = new Cart();
        cart.studentId = Objects.requireNonNull(studentId, "studentId");
        cart.createdAt = Objects.requireNonNull(now, "now");
        return cart;
    }

    /** UC-S01: adds {@code courseId}; a course already in the cart → {@code CART_ITEM_DUPLICATE} (BR-11). */
    public CartItem addItem(Long courseId, Instant now) {
        Objects.requireNonNull(courseId, "courseId");
        Objects.requireNonNull(now, "now");
        if (contains(courseId)) {
            throw new BusinessException(ErrorCode.CART_ITEM_DUPLICATE);
        }
        CartItem item = CartItem.create(this, courseId, now);
        items.add(item);
        return item;
    }

    /**
     * UC-S03: removes the item of {@code courseId}. Returns false when the course is not in the cart; whether that is
     * a 404 or an idempotent success is decided by the service.
     */
    public boolean removeItem(Long courseId) {
        Objects.requireNonNull(courseId, "courseId");
        return items.removeIf(item -> item.getCourseId().equals(courseId));
    }

    /** UC-S05 step 7 (T2): removes the items of the bought courses; other items stay (UC-S05 post-condition). */
    public int removeCourses(Collection<Long> courseIds) {
        Objects.requireNonNull(courseIds, "courseIds");
        Set<Long> bought = Set.copyOf(courseIds);
        int before = items.size();
        items.removeIf(item -> bought.contains(item.getCourseId()));
        return before - items.size();
    }

    public boolean contains(Long courseId) {
        return findItem(courseId).isPresent();
    }

    public Optional<CartItem> findItem(Long courseId) {
        return items.stream().filter(item -> item.getCourseId().equals(courseId)).findFirst();
    }

    public List<Long> courseIds() {
        return items.stream().map(CartItem::getCourseId).toList();
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    /** Read-only view: change the items through {@link #addItem}, {@link #removeItem}, {@link #removeCourses}. */
    public List<CartItem> getItems() {
        return Collections.unmodifiableList(items);
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
        return id != null && id.equals(((Cart) o).getId());
    }

    @Override
    public final int hashCode() {
        return JpaEntities.effectiveClass(this).hashCode();
    }

    @Override
    public String toString() {
        return "Cart[id=" + getId() + ", studentId=" + studentId + "]";
    }
}
