package com.edumy.catalog.dto.request;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Query parameters of UC-G01–G03 {@code GET /catalog/courses?q=&categoryId=&price=&sort=} (bound from the query
 * string; paging is the separate {@code Pageable}). Every parameter is optional; absent or blank = no filter / default.
 * {@code q} (trimmed): part of the course title, case- and accent-insensitive (D-09). {@code price}: {@code free} /
 * {@code paid}. {@code sort}: {@code newest} (default), {@code rating}, {@code price_asc}, {@code price_desc}. Any other
 * {@code price} / {@code sort} value is a field error (400 {@code VALIDATION_FAILED}).
 */
public record CourseSearchRequest(
        @Size(max = 200) String q,
        @Positive Long categoryId,
        @Pattern(regexp = PRICE_CODES, message = "{edumy.catalog.price.invalid}") String price,
        @Pattern(regexp = SORT_CODES, message = "{edumy.catalog.sort.invalid}") String sort) {

    /** UC-G03 price filter. */
    public static final String PRICE_CODES = "free|paid";
    /** UC-G03 sort orders (D-09). */
    public static final String SORT_CODES = "newest|rating|price_asc|price_desc";

    public CourseSearchRequest {
        q = blankToNull(q);
        price = blankToNull(price);
        sort = blankToNull(sort);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
