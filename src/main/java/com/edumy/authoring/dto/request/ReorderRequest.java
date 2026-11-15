package com.edumy.authoring.dto.request;

import java.util.List;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.UniqueElements;

/**
 * UC-I08 / UC-I12 {@code PUT …/chapters/order} and {@code PUT …/lessons/order}: every chapter id of the course (or
 * lesson id of the chapter) exactly once, in the new display order; position = index + 1 (docs/ai/03 §3.4). A list
 * that is not a permutation of the current siblings (missing, extra or duplicate id) → 400 {@code VALIDATION_FAILED}
 * on {@code ids}. The list is bounded ({@value #MAX_IDS} ids, no id twice) before any query runs: a course never
 * comes close to that many chapters or lessons per chapter, so a longer list can only be abuse (P10 MINOR-5).
 */
public record ReorderRequest(
        @NotEmpty
        @Size(max = ReorderRequest.MAX_IDS, message = "{edumy.reorder.ids.too-many}")
        @UniqueElements(message = "{edumy.reorder.ids.duplicate}")
        List<@NotNull @Positive Long> ids) {

    /** Upper bound of one reorder request. */
    public static final int MAX_IDS = 500;
}
