package com.edumy.authoring.api;

/**
 * Relation of a user to a course, for other modules (learning: D-32 content access, BR-38 Q&A replies, UC-I19;
 * commerce: BR-32). Both answers also require an <b>active</b> instructor role in the database (BR-26, D-07: a revoked
 * instructor loses the rights at once, whatever the JWT says). An unknown course gives {@code false}.
 */
public interface CourseAccessApi {

    /** {@code userId} is the main instructor of {@code courseId} (BR-02) and holds an active instructor role. */
    boolean isMainInstructor(Long userId, Long courseId);

    /**
     * "Course instructor" (docs/ai/02): main instructor or co-instructor with an {@code accepted} invitation (pending,
     * declined and removed do not count), and holds an active instructor role (BR-26).
     */
    boolean isCourseInstructor(Long userId, Long courseId);
}
