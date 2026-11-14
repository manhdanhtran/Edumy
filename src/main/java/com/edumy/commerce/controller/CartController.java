package com.edumy.commerce.controller;

import com.edumy.commerce.dto.request.AddCartItemRequest;
import com.edumy.commerce.dto.response.CartResponse;
import com.edumy.commerce.service.CartQueryService;
import com.edumy.commerce.service.CartService;
import com.edumy.shared.security.CurrentUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The caller's cart (UC-S01…S03). Any logged-in user; the cart is always the caller's own. */
@RestController
@RequestMapping("/api/v1/cart")
@Tag(name = "commerce")
public class CartController {

    private final CartQueryService queries;
    private final CartService carts;

    public CartController(CartQueryService queries, CartService carts) {
        this.queries = queries;
        this.carts = carts;
    }

    @GetMapping
    @Operation(summary = "UC-S02 Xem giỏ hàng (mục không còn hợp lệ được đánh dấu, không tính vào tổng)")
    public CartResponse get(@CurrentUserId Long userId) {
        return queries.getCart(userId);
    }

    /** 201 + the whole cart. 404 not sold · 422 free / own course · 409 owned / already in the cart. */
    @PostMapping("/items")
    @Operation(summary = "UC-S01 Thêm khóa học trả phí vào giỏ hàng")
    @ResponseStatus(HttpStatus.CREATED)
    public CartResponse addItem(@CurrentUserId Long userId, @Valid @RequestBody AddCartItemRequest request) {
        carts.addItem(userId, request.courseId());
        return queries.getCart(userId);
    }

    /** 204; a course that is not in the caller's own cart → 404 (NFR-01). */
    @DeleteMapping("/items/{courseId}")
    @Operation(summary = "UC-S03 Xóa khóa học khỏi giỏ hàng")
    public ResponseEntity<Void> removeItem(@CurrentUserId Long userId, @PathVariable Long courseId) {
        carts.removeItem(userId, courseId);
        return ResponseEntity.noContent().build();
    }
}
