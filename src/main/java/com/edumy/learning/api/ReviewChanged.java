package com.edumy.learning.api;

import java.util.Objects;

/**
 * The visible reviews of a course (or one of their ratings / comments) changed: UC-S12 create / re-rate, UC-S13 edit,
 * UC-S14 withdraw, UC-A15 hide, UC-A17 restore. Published inside the transaction. Catalog evicts after commit
 * {@code catalog.rating} (key = {@code courseId}) and {@code catalog.course-detail} (key = {@code slug}, docs/ai/05
 * §5.3) — P08. {@code slug} never changes after creation.
 */
public record ReviewChanged(Long courseId, String slug) {

    public ReviewChanged {
        Objects.requireNonNull(courseId, "courseId");
        Objects.requireNonNull(slug, "slug");
    }
}
