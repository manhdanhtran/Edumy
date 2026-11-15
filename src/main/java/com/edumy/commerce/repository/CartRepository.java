package com.edumy.commerce.repository;

import java.time.Instant;
import java.util.Optional;

import com.edumy.commerce.entity.Cart;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CartRepository extends JpaRepository<Cart, Long> {

    /**
     * UC-S02 {@code GET /cart}, UC-S01 add / UC-S03 remove: the cart of a student with its items (in added order) in
     * one query. Empty when the student has no cart yet (BR-22: created with the first item).
     */
    @EntityGraph(attributePaths = "items")
    Optional<Cart> findWithItemsByStudentId(Long studentId);

    /** T2: id of the student's cart, plain read, no lock and no entity (ADR-011 lock order: T2 never locks carts). */
    @Query("select c.id from Cart c where c.studentId = :studentId")
    Optional<Long> findIdByStudentId(@Param("studentId") Long studentId);

    /**
     * T1 step "lock the cart" (UC-S04): {@code SELECT … FOR UPDATE} of the cart row, so two concurrent "create order"
     * requests of one student are serialised (UC-S04 E3). Items load lazily inside the same transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Cart c where c.studentId = :studentId")
    Optional<Cart> findByStudentIdForUpdate(@Param("studentId") Long studentId);

    /**
     * UC-S01 step 2 / BR-22: creates the cart of {@code studentId} unless it exists. One statement, so two concurrent
     * first adds never fail on {@code uq_carts_student}: the second insert waits for the first transaction, then
     * does nothing. Follow it with {@link #findByStudentIdForUpdate}.
     */
    @Modifying
    @Query(value = """
            INSERT INTO carts (student_id, created_at) VALUES (:studentId, :now)
            ON DUPLICATE KEY UPDATE student_id = student_id""", nativeQuery = true)
    void createIfAbsent(@Param("studentId") Long studentId, @Param("now") Instant now);
}
