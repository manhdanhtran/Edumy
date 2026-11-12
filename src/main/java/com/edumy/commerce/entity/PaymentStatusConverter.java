package com.edumy.commerce.entity;

import com.edumy.shared.jpa.CodeEnumConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class PaymentStatusConverter extends CodeEnumConverter<PaymentStatus> {

    public PaymentStatusConverter() {
        super(PaymentStatus.class);
    }
}
