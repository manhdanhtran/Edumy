package com.edumy.authoring.dto.response;

import java.time.Instant;

/**
 * A category in the admin list (UC-A06…A08 {@code GET /admin/categories}): active and inactive ones, with
 * {@code courseCount} = number of courses using it, whatever their status (D-21: a used category can only be
 * deactivated, never deleted).
 */
public record AdminCategoryResponse(
        Long id,
        String name,
        String slug,
        boolean active,
        long courseCount,
        Instant createdAt,
        Instant updatedAt) {
}
