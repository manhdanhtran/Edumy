package com.edumy.identity.entity;

import com.edumy.shared.jpa.CodeEnumConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class TokenTypeConverter extends CodeEnumConverter<TokenType> {

    public TokenTypeConverter() {
        super(TokenType.class);
    }
}
