package com.edumy.commerce.dto.request;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * Query parameters of UC-A13 {@code GET /admin/orders?orderCode=&email=&status=&createdFrom=&createdTo=} (paging is the
 * separate {@code Pageable}). Every filter is optional; absent / blank = no filter. {@code orderCode}: exact (case
 * insensitive); {@code email}: exact email of the student (an unknown email gives an empty page); {@code status}:
 * {@code pending|paid|cancelled}, anything else is a field error (400 {@code VALIDATION_FAILED}); {@code createdFrom}
 * inclusive / {@code createdTo} exclusive, ISO-8601 instants ({@code 2026-10-01T00:00:00Z}).
 */
public record AdminOrderSearchRequest(
        @Size(max = 40) String orderCode,
        @Size(max = 255) String email,
        @Pattern(regexp = STATUS_CODES, message = "{edumy.order.status.invalid}") String status,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdFrom,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdTo) {

    /** {@code orders.status} codes (ck_orders_status, {@code OrderStatus}). */
    public static final String STATUS_CODES = "pending|paid|cancelled";

    public AdminOrderSearchRequest {
        orderCode = blankToNull(orderCode);
        email = blankToNull(email);
        status = blankToNull(status);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
