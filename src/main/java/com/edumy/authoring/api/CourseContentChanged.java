package com.edumy.authoring.api;

import java.util.Objects;

/**
 * Content visible in the catalog changed on a course that is published or hidden (D-14: edited without re-review),
 * e.g. UC-I03 course information / price. Catalog evicts the course page ({@code catalog.course-detail}, key = slug,
 * docs/ai/05 §5.3) after commit. {@code slug} never changes after creation.
 */
public record CourseContentChanged(Long courseId, String slug) {

    public CourseContentChanged {
        Objects.requireNonNull(courseId, "courseId");
        Objects.requireNonNull(slug, "slug");
    }
}
