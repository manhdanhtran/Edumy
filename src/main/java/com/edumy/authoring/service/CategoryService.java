package com.edumy.authoring.service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.edumy.authoring.api.CategoryChanged;
import com.edumy.authoring.entity.Category;
import com.edumy.authoring.repository.CategoryRepository;
import com.edumy.authoring.repository.CourseRepository;
import com.edumy.identity.api.RoleQueryApi;
import com.edumy.shared.error.BusinessException;
import com.edumy.shared.error.ErrorCode;
import com.edumy.shared.error.FieldViolation;
import com.edumy.shared.security.RoleCode;
import com.edumy.shared.text.SlugGenerator;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Category administration (UC-A06 create, UC-A07 update, UC-A08 delete or deactivate). The controller required the
 * {@code admin} JWT role (route rule + {@code hasRole('ADMIN')}); every write re-checks that the caller still holds an
 * active admin role in the database (BR-26, like every other admin write: a token issued before the role was removed
 * → 403 {@code ACCESS_DENIED}, nothing changes).
 * Name and slug are unique (SRS "trùng tên hoặc slug" → 409 {@code CATEGORY_ALREADY_EXISTS}): a pre-check names the
 * field, and {@code uq_categories_name} / {@code uq_categories_slug} map to the same code under concurrency. Every
 * successful change publishes {@link CategoryChanged} (catalog cache of active categories, evicted after commit).
 */
@Service
public class CategoryService {

    /** {@code categories.slug VARCHAR(120)}. */
    static final int SLUG_MAX_LENGTH = 120;

    private final CategoryRepository categories;
    private final CourseRepository courses;
    private final RoleQueryApi roles;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public CategoryService(CategoryRepository categories, CourseRepository courses, RoleQueryApi roles,
                           ApplicationEventPublisher events, Clock clock) {
        this.categories = categories;
        this.courses = courses;
        this.roles = roles;
        this.events = events;
        this.clock = clock;
    }

    /**
     * UC-A06: new category, active unless {@code active} is {@code false} ("mặc định đang hoạt động"). {@code slug}
     * {@code null} → generated from the name. Returns the new id.
     */
    @Transactional
    public Long create(Long adminId, String name, String slug, Boolean active) {
        requireActiveAdmin(adminId);
        String validName = requireName(name);
        String validSlug = slug != null ? slug : generatedSlug(validName);
        requireUnique(validName, validSlug, null);
        Category category = Category.create(validName, validSlug, clock.instant());
        if (Boolean.FALSE.equals(active)) {
            category.deactivate();
        }
        categories.saveAndFlush(category);
        events.publishEvent(new CategoryChanged(category.getId()));
        return category.getId();
    }

    /**
     * UC-A07: only the given fields change. The slug changes only when given (it stays stable on a rename);
     * {@code active} {@code false}/{@code true} deactivates / reactivates, the current value is a no-op. Unknown id
     * → 404.
     */
    @Transactional
    public void update(Long adminId, Long categoryId, String name, String slug, Boolean active) {
        requireActiveAdmin(adminId);
        Category category = categories.findByIdForUpdate(categoryId).orElseThrow(BusinessException::notFound);
        if (name == null && slug == null && active == null) {
            return;
        }
        if (name != null || slug != null) {
            String newName = name != null ? requireName(name) : category.getName();
            String newSlug = slug != null ? slug : category.getSlug();
            requireUnique(newName, newSlug, categoryId);
            category.rename(newName, newSlug);
        }
        if (active != null && active != category.isActive()) {
            if (active) {
                category.activate();
            } else {
                category.deactivate();
            }
        }
        categories.flush();
        events.publishEvent(new CategoryChanged(categoryId));
    }

    /**
     * UC-A08 (D-21): a category no course uses (any status) is deleted → {@code true}; a used one is only deactivated
     * and its courses keep it → {@code false}. A used category that is already inactive → 409 {@code DATA_CONFLICT}
     * (nothing left to do). The category row is locked first, so a course created concurrently waits for the outcome
     * (and fails on the foreign key if the row was deleted).
     */
    @Transactional
    public boolean deleteOrDeactivate(Long adminId, Long categoryId) {
        requireActiveAdmin(adminId);
        Category category = categories.findByIdForUpdate(categoryId).orElseThrow(BusinessException::notFound);
        boolean deleted;
        if (courses.existsByCategoryId(categoryId)) {
            category.deactivate(); // D-21: a used category is only deactivated
            deleted = false;
        } else {
            categories.delete(category);
            deleted = true;
        }
        categories.flush();
        events.publishEvent(new CategoryChanged(categoryId));
        return deleted;
    }

    /** BR-26: the acting admin must still hold the admin role in the database at the time of the action. */
    private void requireActiveAdmin(Long adminId) {
        Objects.requireNonNull(adminId, "adminId");
        if (!roles.hasActiveRole(adminId, RoleCode.ADMIN)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
    }

    private void requireUnique(String name, String slug, Long ownId) {
        boolean nameTaken = ownId == null ? categories.existsByName(name)
                : categories.existsByNameAndIdNot(name, ownId);
        boolean slugTaken = ownId == null ? categories.existsBySlug(slug)
                : categories.existsBySlugAndIdNot(slug, ownId);
        List<FieldViolation> errors = new ArrayList<>();
        if (nameTaken) {
            errors.add(new FieldViolation("name", "Tên danh mục đã tồn tại"));
        }
        if (slugTaken) {
            errors.add(new FieldViolation("slug", "Slug đã được dùng cho danh mục khác"));
        }
        if (!errors.isEmpty()) {
            throw BusinessException.withErrors(ErrorCode.CATEGORY_ALREADY_EXISTS, errors);
        }
    }

    private static String requireName(String name) {
        String trimmed = name == null ? "" : name.strip();
        if (trimmed.isEmpty()) {
            throw invalid("name", "Tên danh mục không được để trống");
        }
        return trimmed;
    }

    private static String generatedSlug(String name) {
        String slug = SlugGenerator.slugify(name, SLUG_MAX_LENGTH);
        if (slug.isEmpty()) {
            throw invalid("slug", "Không tạo được slug từ tên danh mục, vui lòng nhập slug");
        }
        return slug;
    }

    private static BusinessException invalid(String field, String message) {
        return BusinessException.withErrors(ErrorCode.VALIDATION_FAILED, List.of(new FieldViolation(field, message)));
    }
}
