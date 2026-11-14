package com.edumy.authoring.api;

import java.util.Objects;

/**
 * UC-A11: an admin rejected a {@code pending_review} course with a reason (BR-08). After commit notification mails the
 * reason to the main instructor (SRS §7, D-16), who edits and resubmits (UC-I17).
 *
 * @param reason the stored {@code review_note} (trimmed, never blank)
 */
public record CourseRejected(Long courseId, String slug, String reason) {

    public CourseRejected {
        Objects.requireNonNull(courseId, "courseId");
        Objects.requireNonNull(slug, "slug");
        Objects.requireNonNull(reason, "reason");
    }
}
