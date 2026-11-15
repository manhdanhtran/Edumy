package com.edumy.commerce.dto.request;

import java.util.List;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * UC-S04 {@code POST /orders}. Every field and the whole body are optional; the cart is always the caller's own, never
 * sent.
 * <ul>
 *   <li>{@code paymentMethod}: step 4 "chọn phương thức thanh toán QR"; the only method in v1 is {@code qr}
 *   ({@code null} = {@code qr}).</li>
 *   <li>{@code expectedTotalAmount}, {@code expectedCourseIds} (P11, step 3 confirmation): the total / purchasable
 *   course ids the student saw in {@code GET /cart}; when sent and no longer true → 409 {@code ORDER_CART_CHANGED}
 *   and nothing is created.</li>
 * </ul>
 */
public record CreateOrderRequest(
        @Pattern(regexp = "qr") String paymentMethod,
        @PositiveOrZero Long expectedTotalAmount,
        @Size(max = 100) List<@NotNull @Positive Long> expectedCourseIds) {
}
