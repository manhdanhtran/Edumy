package com.edumy.identity.entity;

import com.edumy.shared.jpa.CodeEnumConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class RevokeReasonConverter extends CodeEnumConverter<RevokeReason> {

    public RevokeReasonConverter() {
        super(RevokeReason.class);
    }
}
