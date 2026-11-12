package com.edumy.commerce.repository;

import java.util.Optional;

import com.edumy.commerce.entity.CartItem;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * {@code cart_items}. Items are written through the {@link com.edumy.commerce.entity.Cart} aggregate (cascade); this
 * repository is for direct lookups only.
 */
public interface CartItemRepository extends JpaRepository<CartItem, Long> {

    /** UC-S03 / duplicate pre-check (BR-11, uq_cart_items_cart_course still decides under concurrency). */
    Optional<CartItem> findByCartIdAndCourseId(Long cartId, Long courseId);

    /** Cart badge (UC-S01 step 4): number of items of a student's cart (0 without a cart). */
    long countByCartStudentId(Long studentId);
}
