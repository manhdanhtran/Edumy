package com.edumy.authoring.api;

import java.util.Objects;

/**
 * UC-A10: an admin approved a {@code pending_review} course, it is {@code published} now. Published inside the approving
 * transaction; after commit notification mails the review result to the main instructor (SRS §7 "Kết quả duyệt, từ
 * chối, ẩn khóa") and catalog evicts the course page (P08). Title and main instructor are read through
 * {@code authoring::api.CourseQueryApi} by the listener; {@code slug} never changes.
 */
public record CourseApproved(Long courseId, String slug) {

    public CourseApproved {
        Objects.requireNonNull(courseId, "courseId");
        Objects.requireNonNull(slug, "slug");
    }
}
