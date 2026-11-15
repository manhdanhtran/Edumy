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

    /**
     * BR-32 relation, <b>whatever the role state</b>: {@code userId} is the main instructor of {@code courseId} or a
     * co-instructor with an {@code accepted} invitation — also after the instructor role was revoked (P10: a former
     * instructor still may not buy, enroll in, review or earn a certificate of a course they built; RBAC §3.3).
     */
    boolean hasInstructorRelation(Long userId, Long courseId);

    /**
     * Batch form of {@link #hasInstructorRelation} (same rule, whatever the role state): those of {@code courseIds} that
     * {@code userId} instructs, in one query (commerce T1 re-validates every cart line, D-26 / BR-32). An empty
     * collection costs no query. The set is immutable.
     */
    java.util.Set<Long> findInstructorRelationCourseIds(Long userId, java.util.Collection<Long> courseIds);

    /**
     * Ids of every course {@code userId} instructs (main or accepted co-instructor), one query; empty without an active
     * instructor role (BR-26). The set is immutable.
     */
    java.util.Set<Long> findInstructedCourseIds(Long userId);
}
