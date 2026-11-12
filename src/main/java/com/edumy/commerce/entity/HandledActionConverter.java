package com.edumy.commerce.entity;

import com.edumy.shared.jpa.CodeEnumConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class HandledActionConverter extends CodeEnumConverter<HandledAction> {

    public HandledActionConverter() {
        super(HandledAction.class);
    }
}
