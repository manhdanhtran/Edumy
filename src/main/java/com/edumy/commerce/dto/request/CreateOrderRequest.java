package com.edumy.commerce.dto.request;

import jakarta.validation.constraints.Pattern;

/**
 * UC-S04 {@code POST /orders} (step 4 "chọn phương thức thanh toán QR"). The only method in v1 is {@code qr}; the field
 * and the whole body are optional ({@code null} = {@code qr}). The cart is always the caller's own, never sent.
 */
public record CreateOrderRequest(@Pattern(regexp = "qr") String paymentMethod) {
}
