package com.edumy.commerce.controller;

import com.edumy.commerce.dto.response.CartResponse;
import com.edumy.commerce.service.CartQueryService;
import com.edumy.shared.security.CurrentUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The caller's cart (UC-S01…S03). Any logged-in user; the cart is always the caller's own. */
@RestController
@RequestMapping("/api/v1/cart")
@Tag(name = "commerce")
public class CartController {

    private final CartQueryService queries;

    public CartController(CartQueryService queries) {
        this.queries = queries;
    }

    @GetMapping
    @Operation(summary = "UC-S02 Xem giỏ hàng (mục không còn hợp lệ được đánh dấu, không tính vào tổng)")
    public CartResponse get(@CurrentUserId Long userId) {
        return queries.getCart(userId);
    }
}
