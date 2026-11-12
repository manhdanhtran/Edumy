package com.edumy.identity.entity;

import com.edumy.shared.jpa.CodeEnumConverter;
import com.edumy.shared.security.RoleCode;
import jakarta.persistence.Converter;

/** {@code roles.code} (ck_roles_code) ↔ {@link RoleCode}. */
@Converter(autoApply = true)
public class RoleCodeConverter extends CodeEnumConverter<RoleCode> {

    public RoleCodeConverter() {
        super(RoleCode.class);
    }
}
