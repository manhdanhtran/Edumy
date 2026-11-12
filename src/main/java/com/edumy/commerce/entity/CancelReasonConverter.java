package com.edumy.commerce.entity;

import com.edumy.shared.jpa.CodeEnumConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class CancelReasonConverter extends CodeEnumConverter<CancelReason> {

    public CancelReasonConverter() {
        super(CancelReason.class);
    }
}
