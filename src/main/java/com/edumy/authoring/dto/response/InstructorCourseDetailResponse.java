package com.edumy.authoring.dto.response;

import java.time.Instant;
import java.util.List;

/**
 * {@code GET /instructor/courses/{id}} (UC-I02 detail, base of UC-I03 and the outline editor UC-I05…I12): the whole
 * course for one of its instructors, chapters → lessons <b>with content</b>, category (and whether it is still active,
 * needed by the BR-07 checklist), the main instructor, every co-instructor invitation of the course with its status,
 * and the review fields (UC-I18). {@code relation} = the caller's relation ({@code main} / {@code co}); {@code status}
 * = lower-case code; {@code isFree} derived. Also readable while {@code pending_review} (BR-33 forbids only editing).
 */
public record InstructorCourseDetailResponse(
        Long id,
        String title,
        String slug,
        String description,
        String thumbnailUrl,
        long price,
        boolean isFree,
        String status,
        String relation,
        Long categoryId,
        String categoryName,
        boolean categoryActive,
        Instructor mainInstructor,
        List<CoInstructor> coInstructors,
        String reviewNote,
        Instant submittedAt,
        Instant reviewedAt,
        Instant publishedAt,
        Instant createdAt,
        Instant updatedAt,
        List<ChapterResponse> chapters) {

    public InstructorCourseDetailResponse {
        coInstructors = coInstructors == null ? List.of() : List.copyOf(coInstructors);
        chapters = chapters == null ? List.of() : List.copyOf(chapters);
    }

    /** The main instructor (BR-02). */
    public record Instructor(Long id, String fullName) {
    }

    /**
     * An invited co-instructor: user id, name, invitation status ({@code pending} / {@code accepted} /
     * {@code declined} / {@code removed}), when invited and when answered ({@code null} while pending).
     */
    public record CoInstructor(Long id, String fullName, String invitationStatus, Instant invitedAt,
                               Instant respondedAt) {
    }
}
