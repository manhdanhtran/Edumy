package com.edumy.learning.entity;

import com.edumy.shared.jpa.CodeEnumConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class ReviewStatusConverter extends CodeEnumConverter<ReviewStatus> {

    public ReviewStatusConverter() {
        super(ReviewStatus.class);
    }
}
