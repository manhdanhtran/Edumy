package com.edumy.authoring.repository;

import java.util.List;

import com.edumy.authoring.entity.Category;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    /** Categories an instructor may pick (UC-I01/I03; BR-07 needs an active category). */
    List<Category> findByActiveTrueOrderByNameAsc();

    /** UC-A06…A08 {@code GET /admin/categories}: active and inactive. */
    List<Category> findAllByOrderByNameAsc();

    /** Pre-check before create/rename (uq_categories_slug still decides under concurrency). */
    boolean existsBySlug(String slug);

    /** Pre-check before create/rename; {@code name} compares accent- and case-insensitively (ai_ci). */
    boolean existsByName(String name);
}
