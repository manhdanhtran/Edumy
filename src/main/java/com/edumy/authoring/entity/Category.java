package com.edumy.authoring.entity;

import java.time.Instant;
import java.util.Objects;

import com.edumy.shared.error.BusinessException;
import com.edumy.shared.error.ErrorCode;
import com.edumy.shared.jpa.JpaEntities;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * {@code categories} (EDU-DDD-001 §5.5). One level only. A category used by courses is never deleted, only
 * deactivated (D-21, UC-A08); whether it is used is a service question ({@code CourseRepository.existsByCategoryId}).
 */
@Entity
@Table(name = "categories")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Unique, accent- and case-insensitively (uq_categories_name under utf8mb4_0900_ai_ci). */
    @Column(name = "name", nullable = false, length = 100)
    private String name;

    /** ck_categories_slug: {@code ^[a-z0-9]+(-[a-z0-9]+)*$}; unique (uq_categories_slug). */
    @Column(name = "slug", nullable = false, length = 120)
    private String slug;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Maintained by MySQL (ON UPDATE CURRENT_TIMESTAMP(6)); null on a freshly persisted instance until reloaded. */
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    /** UC-A06: new active category. {@code name} is stored trimmed. */
    public static Category create(String name, String slug, Instant now) {
        Category category = new Category();
        category.name = Course.requireText(name, "name", "Tên danh mục không được để trống");
        category.slug = Course.requireSlug(slug);
        category.active = true;
        category.createdAt = Objects.requireNonNull(now, "now");
        return category;
    }

    /** UC-A07: new name and slug (uniqueness is decided by uq_categories_name / uq_categories_slug). */
    public void rename(String newName, String newSlug) {
        String validName = Course.requireText(newName, "name", "Tên danh mục không được để trống");
        slug = Course.requireSlug(newSlug);
        name = validName;
    }

    /** UC-A08 on a used category (D-21): active → inactive only. */
    public void deactivate() {
        if (!active) {
            throw new BusinessException(ErrorCode.DATA_CONFLICT);
        }
        active = false;
    }

    /** UC-A07: inactive → active only. */
    public void activate() {
        if (active) {
            throw new BusinessException(ErrorCode.DATA_CONFLICT);
        }
        active = true;
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
        return id != null && id.equals(((Category) o).getId());
    }

    @Override
    public final int hashCode() {
        return JpaEntities.effectiveClass(this).hashCode();
    }

    @Override
    public String toString() {
        return "Category[id=" + getId() + ", slug=" + slug + ", active=" + active + "]";
    }
}
