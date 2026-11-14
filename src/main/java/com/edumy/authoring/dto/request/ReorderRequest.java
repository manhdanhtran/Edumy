package com.edumy.authoring.dto.request;

import java.util.List;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * UC-I08 / UC-I12 {@code PUT …/chapters/order} and {@code PUT …/lessons/order}: every chapter id of the course (or
 * lesson id of the chapter) exactly once, in the new display order; position = index + 1 (docs/ai/03 §3.4). A list
 * that is not a permutation of the current siblings (missing, extra or duplicate id) → 400 {@code VALIDATION_FAILED}
 * on {@code ids}.
 */
public record ReorderRequest(@NotEmpty List<@NotNull @Positive Long> ids) {
}
