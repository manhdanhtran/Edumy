package com.edumy.authoring.entity;

import com.edumy.shared.jpa.CodeEnum;

/** {@code courses.status} (ck_courses_status). Transitions: docs/ai/02 §2.2 (SRS §3.4), enforced by {@link Course}. */
public enum CourseStatus implements CodeEnum {

    DRAFT("draft"),
    PENDING_REVIEW("pending_review"),
    REJECTED("rejected"),
    PUBLISHED("published"),
    HIDDEN("hidden");

    private final String code;

    CourseStatus(String code) {
        this.code = code;
    }

    @Override
    public String code() {
        return code;
    }
}
