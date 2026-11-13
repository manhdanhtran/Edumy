package com.edumy.catalog.dto.response;

/** One active category of {@code GET /catalog/categories} (UC-G03 filter; single level, D-09). */
public record CategoryResponse(Long id, String name, String slug) {
}
