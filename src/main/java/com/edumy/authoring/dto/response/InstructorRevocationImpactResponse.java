package com.edumy.authoring.dto.response;

/**
 * UC-A05 step 1 "Hệ thống hiển thị ảnh hưởng": what revoking the instructor role of a user touches. Courses keep their
 * status after the revocation (D-07); the admin decides separately whether to hide them (UC-A12).
 *
 * @param mainCourseCount         courses whose main instructor is the user, any status
 * @param coInstructorCourseCount courses the user co-teaches (accepted invitation)
 * @param publishedCourseCount    of those (main or co-taught), the courses that are published now
 */
public record InstructorRevocationImpactResponse(long mainCourseCount, long coInstructorCourseCount,
                                                 long publishedCourseCount) {
}
