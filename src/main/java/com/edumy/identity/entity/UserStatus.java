package com.edumy.identity.entity;

import com.edumy.shared.jpa.CodeEnum;

/** {@code users.status} (ck_users_status). Only transitions: active ⇄ locked (docs/ai/02 §2.2). */
public enum UserStatus implements CodeEnum {

    ACTIVE("active"),
    LOCKED("locked");

    private final String code;

    UserStatus(String code) {
        this.code = code;
    }

    @Override
    public String code() {
        return code;
    }
}
