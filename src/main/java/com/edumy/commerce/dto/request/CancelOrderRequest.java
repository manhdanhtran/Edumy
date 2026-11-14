package com.edumy.commerce.dto.request;

/**
 * UC-S16 {@code POST /orders/{orderCode}/cancel}. The SRS asks for no input: the cancel reason of a student's cancel is
 * always {@code user}. The body is optional and carries nothing today; the record keeps the contract open.
 */
public record CancelOrderRequest() {
}
