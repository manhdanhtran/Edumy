package com.edumy.authoring.dto.response;

import java.time.Instant;

/**
 * One row of {@code GET /instructor/courses} (UC-I02): a course where the caller is the main instructor or an accepted
 * co-instructor, any status. {@code relation} = {@code main} / {@code co} (BR-06: only the main instructor manages
 * info, price, invitations and submission). {@code status} = lower-case {@code courses.status} code; {@code isFree} =
 * {@code price == 0} (derived). {@code chapterCount} / {@code lessonCount}: current outline size. {@code reviewNote} =
 * the latest rejection or hiding reason given by the admin (UC-I18), {@code null} when there was none.
 */
public record InstructorCourseSummaryResponse(
        Long id,
        String title,
        String slug,
        String thumbnailUrl,
        long price,
        boolean isFree,
        String status,
        String relation,
        Long categoryId,
        String categoryName,
        long chapterCount,
        long lessonCount,
        String reviewNote,
        Instant submittedAt,
        Instant publishedAt,
        Instant createdAt,
        Instant updatedAt) {
}
