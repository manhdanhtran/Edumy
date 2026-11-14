package com.edumy.authoring.dto.request;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * UC-A06 {@code POST /admin/categories} and UC-A07 {@code PATCH /admin/categories/{id}}.
 * <ul>
 *   <li>{@code name}: required on create (blank → 400 {@code VALIDATION_FAILED}), ≤100 characters, stored trimmed;</li>
 *   <li>{@code slug}: optional; when absent the create generates it from the name ({@code SlugGenerator}) and the
 *       update keeps the current one; format of {@code ck_categories_slug}, ≤120 characters;</li>
 *   <li>{@code active}: optional; create defaults to {@code true} (SRS UC-A06), update false/true = deactivate /
 *       reactivate.</li>
 * </ul>
 * PATCH semantics: a field that is absent (or {@code null}) is left unchanged.
 */
public record CategoryRequest(
        @Size(max = 100) String name,
        @Size(max = 120) @Pattern(regexp = "[a-z0-9]+(-[a-z0-9]+)*", message = "{edumy.category.slug.invalid}")
        String slug,
        Boolean active) {
}
