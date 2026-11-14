package com.edumy.learning.service;

import com.edumy.authoring.api.CourseAccessApi;
import com.edumy.authoring.api.CourseQueryApi;
import com.edumy.authoring.api.CourseSummary;
import com.edumy.learning.repository.EnrollmentRepository;
import com.edumy.shared.error.BusinessException;
import com.edumy.shared.error.ErrorCode;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** UC-S15 free enrollment (BR-09, BR-17, BR-32; D-02: no verified email needed). */
@Service
public class EnrollmentService {

    private static final Logger log = LoggerFactory.getLogger(EnrollmentService.class);

    private final EnrollmentRepository enrollments;
    private final CourseQueryApi courses;
    private final CourseAccessApi courseAccess;
    private final Clock clock;

    public EnrollmentService(EnrollmentRepository enrollments, CourseQueryApi courses, CourseAccessApi courseAccess,
                             Clock clock) {
        this.enrollments = enrollments;
        this.courses = courses;
        this.courseAccess = courseAccess;
        this.clock = clock;
    }

    /**
     * UC-S15 in SRS order. Returns {@code true} when the enrollment was created, {@code false} when the student
     * already had one (step 4: "mở khóa đã ghi danh, không tạo thêm"). The insert is {@code INSERT IGNORE}, so two
     * concurrent requests of one student create exactly one row (BR-17, {@code uq_enrollments_student_course}).
     */
    @Transactional
    public boolean enrollFree(Long studentId, Long courseId) {
        CourseSummary course = courses.findSummaries(List.of(courseId)).get(courseId);
        // E1 / BR-31: only published courses; unknown, draft, pending, rejected and hidden look the same (404)
        if (course == null || !course.published()) {
            throw BusinessException.notFound();
        }
        // BR-09: paid courses go through the cart and an order
        if (course.price() != 0) {
            throw new BusinessException(ErrorCode.ENROLLMENT_COURSE_NOT_FREE);
        }
        // step 1 / E2 / BR-32 / D-12: main (whatever the role state) or accepted co-instructor
        if (course.mainInstructorId().equals(studentId) || courseAccess.isCourseInstructor(studentId, courseId)) {
            throw new BusinessException(ErrorCode.ENROLLMENT_OWN_COURSE);
        }
        boolean created = enrollments.insertFreeIfAbsent(studentId, courseId, clock.instant()) == 1;
        if (created) {
            log.info("Free enrollment created: userId={}, courseId={}", studentId, courseId);
        }
        return created;
    }
}
