package com.edumy.shared.security;

import com.edumy.shared.jpa.CodeEnum;

/** {@code roles.code} values (docs/ai/02 §2.1). One account may hold several roles (BR-01). */
public enum RoleCode implements CodeEnum {

    STUDENT("student"),
    INSTRUCTOR("instructor"),
    ADMIN("admin");

    private final String code;

    RoleCode(String code) {
        this.code = code;
    }

    @Override
    public String code() {
        return code;
    }
}
