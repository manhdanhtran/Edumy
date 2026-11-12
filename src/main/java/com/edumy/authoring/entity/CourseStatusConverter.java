package com.edumy.authoring.entity;

import com.edumy.shared.jpa.CodeEnumConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class CourseStatusConverter extends CodeEnumConverter<CourseStatus> {

    public CourseStatusConverter() {
        super(CourseStatus.class);
    }
}
