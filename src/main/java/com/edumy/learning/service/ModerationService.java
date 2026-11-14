package com.edumy.learning.service;

import java.time.Clock;
import java.util.List;
import java.util.Objects;

import com.edumy.authoring.api.CourseQueryApi;
import com.edumy.authoring.api.CourseSummary;
import com.edumy.identity.api.RoleQueryApi;
import com.edumy.identity.api.UserQueryApi;
import com.edumy.identity.api.UserSummary;
import com.edumy.learning.api.ReviewChanged;
import com.edumy.learning.dto.response.AnswerResponse;
import com.edumy.learning.dto.response.QuestionThreadResponse;
import com.edumy.learning.dto.response.ReviewResponse;
import com.edumy.learning.entity.ContentStatus;
import com.edumy.learning.entity.CourseQuestion;
import com.edumy.learning.entity.CourseReview;
import com.edumy.learning.entity.QuestionAnswer;
import com.edumy.learning.mapper.QaMapper;
import com.edumy.learning.mapper.ReviewMapper;
import com.edumy.learning.repository.CourseQuestionRepository;
import com.edumy.learning.repository.CourseReviewRepository;
import com.edumy.learning.repository.QuestionAnswerRepository;
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
 * Admin moderation of learning content: UC-A15 hide a review, UC-A17 restore it, UC-A19 hide / restore a question or an
 * answer. The controller already required the {@code admin} JWT role; here the admin role is re-checked in the database
 * (BR-26, like {@code CourseModerationService}) and the row is locked: two admins, or an admin and the author, are
 * serialized. Who and when are stored ({@code moderated_by}, {@code moderated_at}, BR-35, BR-41); the SRS asks for no
 * reason. Q&amp;A: a {@code deleted} question or answer (or an answer of a deleted question) is gone for everybody,
 * the admin included (SRS §3.4.6, "final") → 404; hide of a hidden / restore of a visible item → 409.
 */
@Service
public class ModerationService {

    private static final Logger log = LoggerFactory.getLogger(ModerationService.class);

    private final CourseReviewRepository reviews;
    private final CourseQuestionRepository questions;
    private final QuestionAnswerRepository answers;
    private final CourseQueryApi courses;
    private final RoleQueryApi roles;
    private final UserQueryApi users;
    private final ReviewMapper mapper;
    private final QaMapper qaMapper;
    private final QaQueryService qaQueries;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @PersistenceContext
    private EntityManager em;

    public ModerationService(CourseReviewRepository reviews, CourseQuestionRepository questions,
                             QuestionAnswerRepository answers, CourseQueryApi courses, RoleQueryApi roles,
                             UserQueryApi users, ReviewMapper mapper, QaMapper qaMapper, QaQueryService qaQueries,
                             ApplicationEventPublisher events, Clock clock) {
        this.reviews = reviews;
        this.questions = questions;
        this.answers = answers;
        this.courses = courses;
        this.roles = roles;
        this.users = users;
        this.mapper = mapper;
        this.qaMapper = qaMapper;
        this.qaQueries = qaQueries;
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

    /** UC-A19: visible → hidden; the thread leaves the lists (only its asker and admins still see it, BR-41). */
    @Transactional
    public QuestionThreadResponse hideQuestion(Long adminId, Long questionId, String note) {
        CourseQuestion question = lockQuestionAsAdmin(adminId, questionId);
        question.hide(adminId, clock.instant());
        QuestionThreadResponse response = flushAndReadThread(adminId, question);
        log.info("Question hidden: questionId={}, by adminId={}, note={}", questionId, adminId, note);
        return response;
    }

    /** UC-A19: hidden → visible (admin only, BR-41); any other status → 409 {@code DATA_CONFLICT}. */
    @Transactional
    public QuestionThreadResponse restoreQuestion(Long adminId, Long questionId, String note) {
        CourseQuestion question = lockQuestionAsAdmin(adminId, questionId);
        question.restore(adminId, clock.instant());
        QuestionThreadResponse response = flushAndReadThread(adminId, question);
        log.info("Question restored: questionId={}, by adminId={}, note={}", questionId, adminId, note);
        return response;
    }

    /** UC-A19: visible → hidden; already hidden → 409 {@code DATA_CONFLICT}. */
    @Transactional
    public AnswerResponse hideAnswer(Long adminId, Long answerId, String note) {
        QuestionAnswer answer = lockAnswerAsAdmin(adminId, answerId);
        answer.hide(adminId, clock.instant());
        AnswerResponse response = flushAndReadAnswer(answer);
        log.info("Answer hidden: answerId={}, by adminId={}, note={}", answerId, adminId, note);
        return response;
    }

    /** UC-A19: hidden → visible (admin only, BR-41); any other status → 409 {@code DATA_CONFLICT}. */
    @Transactional
    public AnswerResponse restoreAnswer(Long adminId, Long answerId, String note) {
        QuestionAnswer answer = lockAnswerAsAdmin(adminId, answerId);
        answer.restore(adminId, clock.instant());
        AnswerResponse response = flushAndReadAnswer(answer);
        log.info("Answer restored: answerId={}, by adminId={}, note={}", answerId, adminId, note);
        return response;
    }

    private void requireAdmin(Long adminId) {
        Objects.requireNonNull(adminId, "adminId");
        if (!roles.hasActiveRole(adminId, RoleCode.ADMIN)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED); // BR-26: admin role re-checked at action time
        }
    }

    private CourseReview lockAsAdmin(Long adminId, Long reviewId) {
        requireAdmin(adminId);
        return reviews.findByIdForUpdate(reviewId).orElseThrow(BusinessException::notFound);
    }

    private CourseQuestion lockQuestionAsAdmin(Long adminId, Long questionId) {
        requireAdmin(adminId);
        CourseQuestion question = questions.findByIdForUpdate(questionId).orElseThrow(BusinessException::notFound);
        if (question.getVisibilityStatus() == ContentStatus.DELETED) {
            throw BusinessException.notFound(); // deleted is final and invisible to everybody
        }
        return question;
    }

    private QuestionAnswer lockAnswerAsAdmin(Long adminId, Long answerId) {
        requireAdmin(adminId);
        QuestionAnswer answer = answers.findByIdForUpdate(answerId).orElseThrow(BusinessException::notFound);
        if (answer.getVisibilityStatus() == ContentStatus.DELETED
                || answer.getQuestion().getVisibilityStatus() == ContentStatus.DELETED) {
            throw BusinessException.notFound();
        }
        return answer;
    }

    private QuestionThreadResponse flushAndReadThread(Long adminId, CourseQuestion question) {
        questions.flush();
        em.refresh(question);
        return qaQueries.thread(adminId, question.getId());
    }

    private AnswerResponse flushAndReadAnswer(QuestionAnswer answer) {
        answers.flush();
        em.refresh(answer);
        UserSummary author = users.findSummaries(List.of(answer.getAuthorId())).get(answer.getAuthorId());
        return qaMapper.toAnswer(answer, author);
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
