package com.edumy.commerce.repository;

import java.util.Collection;
import java.util.Optional;

import com.edumy.commerce.entity.CartItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * {@code cart_items}. Items are written through the {@link com.edumy.commerce.entity.Cart} aggregate (cascade); this
 * repository is for direct lookups only.
 */
public interface CartItemRepository extends JpaRepository<CartItem, Long> {

    /** UC-S03 / duplicate pre-check (BR-11, uq_cart_items_cart_course still decides under concurrency). */
    Optional<CartItem> findByCartIdAndCourseId(Long cartId, Long courseId);

    /** Cart badge (UC-S01 step 4): number of items of a student's cart (0 without a cart). */
    long countByCartStudentId(Long studentId);

    /** Number of carts holding {@code courseId} (an item is unique per cart, BR-11): CourseHiddenListener log. */
    long countByCourseId(Long courseId);

    /**
     * T2 (UC-S05 step 7): the bought courses leave the cart; the other lines stay (UC-S05 post-condition). Native
     * delete by the cart id resolved beforehand with a plain read ({@link CartRepository#findIdByStudentId}): T2 never
     * locks the {@code carts} row, which T1 locks before the order (ADR-011 lock order). Does not clear the
     * persistence context (the locked order / payment stay managed). Pass a non-empty {@code courseIds}.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "DELETE FROM cart_items WHERE cart_id = :cartId AND course_id IN (:courseIds)", nativeQuery = true)
    int deleteByCartIdAndCourseIds(@Param("cartId") Long cartId, @Param("courseIds") Collection<Long> courseIds);
}
