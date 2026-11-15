package com.edumy.commerce.service;

import java.util.List;

/**
 * UC-S04 input (step 3 confirmation, P11 decision Q2): what the student saw before confirming — the total and / or the
 * course ids of the purchasable lines. {@code null} = not checked (the cart is taken as it is now).
 */
public record CreateOrderCommand(Long expectedTotalAmount, List<Long> expectedCourseIds) {

    public CreateOrderCommand {
        expectedCourseIds = expectedCourseIds == null ? null : List.copyOf(expectedCourseIds);
    }

    public static CreateOrderCommand unchecked() {
        return new CreateOrderCommand(null, null);
    }
}
