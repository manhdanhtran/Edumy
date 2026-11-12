package com.edumy.authoring.entity;

import com.edumy.shared.jpa.CodeEnum;

/**
 * {@code course_co_instructors.invitation_status} (ck_cci_status). Transitions: docs/ai/02 §2.2, enforced by
 * {@link CourseCoInstructor}.
 */
public enum InvitationStatus implements CodeEnum {

    PENDING("pending"),
    ACCEPTED("accepted"),
    DECLINED("declined"),
    REMOVED("removed");

    private final String code;

    InvitationStatus(String code) {
        this.code = code;
    }

    @Override
    public String code() {
        return code;
    }
}
