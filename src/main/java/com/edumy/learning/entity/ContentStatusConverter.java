package com.edumy.learning.entity;

import com.edumy.shared.jpa.CodeEnumConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class ContentStatusConverter extends CodeEnumConverter<ContentStatus> {

    public ContentStatusConverter() {
        super(ContentStatus.class);
    }
}
