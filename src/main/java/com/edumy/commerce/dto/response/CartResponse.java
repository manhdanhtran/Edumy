package com.edumy.commerce.dto.response;

import java.time.Instant;
import java.util.List;

/**
 * UC-S02 {@code GET /cart}: the caller's cart, items in the order they were added (an empty cart is {@code items: []},
 * {@code totalAmount: 0}; nothing is created by reading, BR-22). Each line shows the <b>current</b> course price.
 * {@code purchasable = false} marks a line that UC-S04 would drop (D-26) and that is not in {@code totalAmount}, with
 * {@code unavailableReason}: {@code course_unavailable} (hidden / not published, BR-31), {@code already_owned} (BR-10),
 * {@code own_course} (main instructor, BR-32) or {@code now_free} (price became 0, BR-09: enroll for free instead);
 * {@code null} when purchasable. {@code totalAmount} = sum of the prices of the purchasable lines.
 */
public record CartResponse(
        List<Item> items,
        int itemCount,
        int purchasableCount,
        long totalAmount) {

    /** One cart line. */
    public record Item(
            Long courseId,
            String title,
            String slug,
            String thumbnailUrl,
            long price,
            boolean isFree,
            Instructor instructor,
            Instant addedAt,
            boolean purchasable,
            String unavailableReason) {
    }

    /** Main instructor of the course. */
    public record Instructor(Long id, String fullName) {
    }
}
