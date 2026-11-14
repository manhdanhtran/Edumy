package com.edumy.authoring.api;

import java.util.Objects;

/**
 * UC-A12: an admin hid a {@code published} course with a reason (BR-08): not listed, searched, sold or newly enrolled
 * any more; enrolled students keep access (BR-31). After commit: notification mails the main instructor (step 6),
 * commerce flags cart items (step 4, {@code CourseHiddenListener}), catalog evicts the course page (P08).
 *
 * @param reason the stored {@code review_note} (trimmed, never blank)
 */
public record CourseHidden(Long courseId, String slug, String reason) {

    public CourseHidden {
        Objects.requireNonNull(courseId, "courseId");
        Objects.requireNonNull(slug, "slug");
        Objects.requireNonNull(reason, "reason");
    }
}
