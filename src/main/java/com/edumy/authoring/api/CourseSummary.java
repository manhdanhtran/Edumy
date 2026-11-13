package com.edumy.authoring.api;

/**
 * Public facts about a course for other modules (commerce: cart lines, order lines). {@code published} is
 * {@code status == published} (BR-31: only published courses are sold); {@code mainInstructorId} lets the caller tell
 * "own course" (BR-32) without another query.
 */
public record CourseSummary(Long id, String title, String slug, String thumbnailUrl, long price, boolean published,
                            Long mainInstructorId) {
}
