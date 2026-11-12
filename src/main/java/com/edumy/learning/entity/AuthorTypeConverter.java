package com.edumy.learning.entity;

import com.edumy.shared.jpa.CodeEnumConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class AuthorTypeConverter extends CodeEnumConverter<AuthorType> {

    public AuthorTypeConverter() {
        super(AuthorType.class);
    }
}
