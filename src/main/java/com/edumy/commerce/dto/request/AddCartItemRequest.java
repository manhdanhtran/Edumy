package com.edumy.commerce.dto.request;

import jakarta.validation.constraints.NotNull;

/** UC-S01 {@code POST /cart/items}: the course to add to the caller's cart. */
public record AddCartItemRequest(@NotNull Long courseId) {
}
