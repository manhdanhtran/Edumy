package com.edumy.learning.entity;

import com.edumy.shared.jpa.CodeEnum;

/**
 * {@code enrollments.enroll_source} (ck_enrollments_source): free → no order, purchase → its order
 * (ck_enrollments_source_order, BR-14).
 */
public enum EnrollmentSource implements CodeEnum {

    FREE("free"),
    PURCHASE("purchase");

    private final String code;

    EnrollmentSource(String code) {
        this.code = code;
    }

    @Override
    public String code() {
        return code;
    }
}
