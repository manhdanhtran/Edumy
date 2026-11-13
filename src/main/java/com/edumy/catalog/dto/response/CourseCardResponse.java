package com.edumy.catalog.dto.response;

import java.time.Instant;

/**
 * One course card of the catalog list (UC-G01): published course, main instructor's name, price or "free"
 * ({@code isFree} = {@code price == 0}, derived), average rating (one decimal, {@code null} when there is none) and
 * number of the <b>visible</b> reviews only (D-10).
 */
public record CourseCardResponse(
        Long id,
        String slug,
        String title,
        String thumbnailUrl,
        long price,
        boolean isFree,
        Long categoryId,
        String categoryName,
        String instructorName,
        Double averageRating,
        long ratingCount,
        Instant publishedAt) {
}
