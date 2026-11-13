package com.edumy.authoring.repository;

import java.time.Instant;
import java.util.List;

import com.edumy.authoring.entity.Category;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

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

    /** Pre-check before create/rename (uq_categories_slug still decides under concurrency). */
    boolean existsBySlug(String slug);

    /** Pre-check before create/rename; {@code name} compares accent- and case-insensitively (ai_ci). */
    boolean existsByName(String name);

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
