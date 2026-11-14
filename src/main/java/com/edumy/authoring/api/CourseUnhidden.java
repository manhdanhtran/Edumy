package com.edumy.authoring.api;

import java.util.Objects;

/**
 * UC-A16: an admin made a {@code hidden} course {@code published} again (D-20); it is back in the catalog. No mail
 * (SRS §7 lists none); catalog evicts the course page after commit (P08).
 */
public record CourseUnhidden(Long courseId, String slug) {

    public CourseUnhidden {
        Objects.requireNonNull(courseId, "courseId");
        Objects.requireNonNull(slug, "slug");
    }
}
