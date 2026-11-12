package com.edumy.learning.entity;

import com.edumy.shared.jpa.CodeEnumConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class EnrollmentSourceConverter extends CodeEnumConverter<EnrollmentSource> {

    public EnrollmentSourceConverter() {
        super(EnrollmentSource.class);
    }
}
