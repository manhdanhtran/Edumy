package com.edumy.commerce.entity;

import com.edumy.shared.jpa.CodeEnumConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class OrderStatusConverter extends CodeEnumConverter<OrderStatus> {

    public OrderStatusConverter() {
        super(OrderStatus.class);
    }
}
