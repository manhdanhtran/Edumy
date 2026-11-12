package com.edumy.learning.entity;

import com.edumy.shared.jpa.CodeEnum;

/**
 * {@code course_reviews.visibility_status} (ck_course_reviews_visibility). Transitions: docs/ai/02 §2.2,
 * enforced by {@link CourseReview}.
 */
public enum ReviewStatus implements CodeEnum {

    VISIBLE("visible"),
    WITHDRAWN("withdrawn"),
    HIDDEN("hidden");

    private final String code;

    ReviewStatus(String code) {
        this.code = code;
    }

    @Override
    public String code() {
        return code;
    }
}
