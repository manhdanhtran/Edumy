package com.edumy.authoring.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.edumy.authoring.entity.Category;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    /** Categories an instructor may pick (UC-I01/I03; BR-07 needs an active category). */
    List<Category> findByActiveTrueOrderByNameAsc();

    /** UC-A06…A08 {@code GET /admin/categories}: active and inactive. */
    List<Category> findAllByOrderByNameAsc();

    /**
     * UC-A06…A08 {@code GET /admin/categories}: every category (active and inactive) with the number of courses using it
     * (any course status; D-21: a used category can only be deactivated), by name — one statement.
     */
    @Query("""
            select cat.id as id, cat.name as name, cat.slug as slug, cat.active as active, count(c.id) as courseCount,
                   cat.createdAt as createdAt, cat.updatedAt as updatedAt
            from Category cat left join Course c on c.category = cat
            group by cat.id, cat.name, cat.slug, cat.active, cat.createdAt, cat.updatedAt
            order by cat.name asc, cat.id asc
            """)
    List<AdminCategoryView> findAllWithCourseCount();

    /** One row of {@link #findAllWithCourseCount} (answer of UC-A06…A08 writes). */
    @Query("""
            select cat.id as id, cat.name as name, cat.slug as slug, cat.active as active, count(c.id) as courseCount,
                   cat.createdAt as createdAt, cat.updatedAt as updatedAt
            from Category cat left join Course c on c.category = cat
            where cat.id = :id
            group by cat.id, cat.name, cat.slug, cat.active, cat.createdAt, cat.updatedAt
            """)
    Optional<AdminCategoryView> findWithCourseCountById(@Param("id") Long id);

    /** Row lock for UC-A07 / UC-A08 (rename, (de)activate, delete-or-deactivate decided on a stable row). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select cat from Category cat where cat.id = :id")
    Optional<Category> findByIdForUpdate(@Param("id") Long id);

    /** Pre-check before create/rename (uq_categories_slug still decides under concurrency). */
    boolean existsBySlug(String slug);

    /** Pre-check before create/rename; {@code name} compares accent- and case-insensitively (ai_ci). */
    boolean existsByName(String name);

    /** UC-A07 pre-check: another category already has this slug. */
    boolean existsBySlugAndIdNot(String slug, Long id);

    /** UC-A07 pre-check: another category already has this name (ai_ci, like uq_categories_name). */
    boolean existsByNameAndIdNot(String name, Long id);

    /** Row of {@link #findAllWithCourseCount}. */
    interface AdminCategoryView {
        Long getId();

        String getName();

        String getSlug();

        boolean isActive();

        long getCourseCount();

        Instant getCreatedAt();

        Instant getUpdatedAt();
    }
}
