package com.edumy.learning.service;

import java.time.Clock;
import java.util.List;

import com.edumy.authoring.api.CourseQueryApi;
import com.edumy.authoring.api.CourseSummary;
import com.edumy.learning.api.ReviewChanged;
import com.edumy.learning.dto.response.ReviewResponse;
import com.edumy.learning.entity.CourseReview;
import com.edumy.learning.entity.Enrollment;
import com.edumy.learning.mapper.ReviewMapper;
import com.edumy.learning.repository.CourseReviewRepository;
import com.edumy.learning.repository.EnrollmentRepository;
import com.edumy.shared.error.BusinessException;
import com.edumy.shared.error.ErrorCode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Student side of the course reviews: UC-S12 create / re-rate, UC-S13 edit, UC-S14 withdraw (state machine docs/ai/02
 * §2.2, enforced by {@link CourseReview}). The student id always comes from the JWT; a review is only reachable through
 * the caller's own enrollment (NFR-01). Every change publishes {@link ReviewChanged} inside the transaction (catalog
 * evicts its rating cache after commit, P08).
 * <p>UC-S12 locks the enrollment row ({@code FOR UPDATE}, READ COMMITTED) so that two concurrent requests of one student
 * are serialized: the second sees the first one's review and gets 409 instead of tripping
 * {@code uq_course_reviews_enrollment} (BR-18).
 */
@Service
public class ReviewService {

    private static final Logger log = LoggerFactory.getLogger(ReviewService.class);

    /** {@code created} = a new row (HTTP 201); false = the withdrawn review of the enrollment was re-rated (200). */
    public record Result(ReviewResponse review, boolean created) {
    }

    private final CourseReviewRepository reviews;
    private final EnrollmentRepository enrollments;
    private final CourseQueryApi courses;
    private final ReviewMapper mapper;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @PersistenceContext
    private EntityManager em;

    public ReviewService(CourseReviewRepository reviews, EnrollmentRepository enrollments, CourseQueryApi courses,
                         ReviewMapper mapper, ApplicationEventPublisher events, Clock clock) {
        this.reviews = reviews;
        this.enrollments = enrollments;
        this.courses = courses;
        this.mapper = mapper;
        this.events = events;
        this.clock = clock;
    }

    /**
     * UC-S12 in SRS order: the course must exist (unknown → 404); E1 no enrollment → 403 {@code ENROLLMENT_REQUIRED}
     * for a <b>published</b> course only (its id is public, so the SPA sends the caller to the course page, UC-S09) —
     * a draft / pending / rejected / hidden course without enrollment answers the 404 of an unknown id (NFR-01, P10
     * 403/404 principle; enrolled students still review hidden courses, BR-31);
     * no review yet → new visible review; withdrawn → the same row is re-rated and visible again (step 3); visible →
     * 409 {@code DATA_CONFLICT} (step 3 sends the student to UC-S13, BR-18); hidden by an admin → 409
     * {@code REVIEW_HIDDEN_BY_ADMIN} (E2, BR-35).
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Result create(Long studentId, Long courseId, int rating, String comment) {
        CourseSummary course = courses.findSummaries(List.of(courseId)).get(courseId);
        if (course == null) {
            throw BusinessException.notFound();
        }
        Enrollment enrollment = enrollments.findByStudentIdAndCourseIdForUpdate(studentId, courseId)
                .orElseThrow(() -> course.published() ? new BusinessException(ErrorCode.ENROLLMENT_REQUIRED)
                        : BusinessException.notFound());
        CourseReview review = reviews.findByEnrollmentId(enrollment.getId()).orElse(null);
        boolean created = review == null;
        if (created) {
            review = reviews.save(CourseReview.create(enrollment, rating, comment, clock.instant()));
        } else {
            if (review.isVisible()) {
                throw new BusinessException(ErrorCode.DATA_CONFLICT); // BR-18: one review per enrollment, use UC-S13
            }
            review.update(rating, comment); // hidden → REVIEW_HIDDEN_BY_ADMIN (BR-35); withdrawn → visible
        }
        ReviewResponse response = flushAndRead(review);
        events.publishEvent(new ReviewChanged(courseId, course.slug()));
        log.info("Review {}: reviewId={}, courseId={}, userId={}", created ? "created" : "re-rated", review.getId(),
                courseId, studentId);
        return new Result(response, created);
    }

    /** UC-S13: own visible review → new rating and comment. Hidden → 409 {@code REVIEW_HIDDEN_BY_ADMIN}; withdrawn → 409. */
    @Transactional
    public ReviewResponse update(Long studentId, Long reviewId, int rating, String comment) {
        CourseReview review = reviews.findOwnForUpdate(reviewId, studentId).orElseThrow(BusinessException::notFound);
        review.edit(rating, comment);
        ReviewResponse response = flushAndRead(review);
        publish(review);
        log.info("Review edited: reviewId={}, userId={}", reviewId, studentId);
        return response;
    }

    /** UC-S14: own visible review → withdrawn (not a delete, it leaves the average and can be re-rated). */
    @Transactional
    public void withdraw(Long studentId, Long reviewId) {
        CourseReview review = reviews.findOwnForUpdate(reviewId, studentId).orElseThrow(BusinessException::notFound);
        review.withdraw();
        reviews.flush();
        publish(review);
        log.info("Review withdrawn: reviewId={}, userId={}", reviewId, studentId);
    }

    /** Flushes, reloads the DB-maintained {@code updated_at} and maps. */
    private ReviewResponse flushAndRead(CourseReview review) {
        reviews.flush();
        em.refresh(review);
        return mapper.toResponse(review);
    }

    private void publish(CourseReview review) {
        Long courseId = review.getEnrollment().getCourseId();
        CourseSummary course = courses.findSummaries(List.of(courseId)).get(courseId);
        events.publishEvent(new ReviewChanged(courseId, course.slug()));
    }
}
