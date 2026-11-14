package com.edumy.learning.service;

import java.time.Clock;
import java.util.List;
import java.util.Objects;

import com.edumy.authoring.api.CourseQueryApi;
import com.edumy.authoring.api.CourseSummary;
import com.edumy.identity.api.RoleQueryApi;
import com.edumy.learning.api.ReviewChanged;
import com.edumy.learning.dto.response.ReviewResponse;
import com.edumy.learning.entity.CourseReview;
import com.edumy.learning.mapper.ReviewMapper;
import com.edumy.learning.repository.CourseReviewRepository;
import com.edumy.shared.error.BusinessException;
import com.edumy.shared.error.ErrorCode;
import com.edumy.shared.security.RoleCode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin moderation of learning content: UC-A15 hide a review, UC-A17 restore it (UC-A19 questions and answers are added
 * by the same service). The controller already required the {@code admin} JWT role; here the admin role is re-checked in
 * the database (BR-26, like {@code CourseModerationService}) and the row is locked: two admins, or an admin and the
 * author, are serialized. Who and when are stored ({@code moderated_by}, {@code moderated_at}, BR-35); the SRS asks for
 * no reason.
 */
@Service
public class ModerationService {

    private static final Logger log = LoggerFactory.getLogger(ModerationService.class);

    private final CourseReviewRepository reviews;
    private final CourseQueryApi courses;
    private final RoleQueryApi roles;
    private final ReviewMapper mapper;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @PersistenceContext
    private EntityManager em;

    public ModerationService(CourseReviewRepository reviews, CourseQueryApi courses, RoleQueryApi roles,
                             ReviewMapper mapper, ApplicationEventPublisher events, Clock clock) {
        this.reviews = reviews;
        this.courses = courses;
        this.roles = roles;
        this.mapper = mapper;
        this.events = events;
        this.clock = clock;
    }

    /** UC-A15: visible | withdrawn → hidden; already hidden → 409 {@code DATA_CONFLICT}. */
    @Transactional
    public ReviewResponse hideReview(Long adminId, Long reviewId, String note) {
        CourseReview review = lockAsAdmin(adminId, reviewId);
        review.hide(adminId, clock.instant());
        ReviewResponse response = flushAndRead(review);
        publish(review);
        log.info("Review hidden: reviewId={}, by adminId={}, note={}", reviewId, adminId, note);
        return response;
    }

    /** UC-A17: hidden → visible (admin only, BR-35); any other status → 409 {@code DATA_CONFLICT}. */
    @Transactional
    public ReviewResponse restoreReview(Long adminId, Long reviewId, String note) {
        CourseReview review = lockAsAdmin(adminId, reviewId);
        review.restore(adminId, clock.instant());
        ReviewResponse response = flushAndRead(review);
        publish(review);
        log.info("Review restored: reviewId={}, by adminId={}, note={}", reviewId, adminId, note);
        return response;
    }

    private CourseReview lockAsAdmin(Long adminId, Long reviewId) {
        Objects.requireNonNull(adminId, "adminId");
        if (!roles.hasActiveRole(adminId, RoleCode.ADMIN)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED); // BR-26: admin role re-checked at action time
        }
        return reviews.findByIdForUpdate(reviewId).orElseThrow(BusinessException::notFound);
    }

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
