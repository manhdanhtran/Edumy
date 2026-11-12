package com.edumy.authoring.entity;

import com.edumy.shared.jpa.CodeEnumConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class LessonTypeConverter extends CodeEnumConverter<LessonType> {

    public LessonTypeConverter() {
        super(LessonType.class);
    }
}
