package com.edumy.learning.service;

import java.time.Clock;
import java.util.List;

import com.edumy.authoring.api.CourseAccessApi;
import com.edumy.identity.api.UserQueryApi;
import com.edumy.identity.api.UserSummary;
import com.edumy.learning.api.QuestionAnsweredByInstructor;
import com.edumy.learning.dto.response.AnswerResponse;
import com.edumy.learning.entity.AuthorType;
import com.edumy.learning.entity.ContentStatus;
import com.edumy.learning.entity.CourseQuestion;
import com.edumy.learning.entity.QuestionAnswer;
import com.edumy.learning.mapper.QaMapper;
import com.edumy.learning.repository.CourseQuestionRepository;
import com.edumy.learning.repository.QuestionAnswerRepository;
import com.edumy.shared.error.BusinessException;
import com.edumy.shared.error.ErrorCode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Replies in a question thread: UC-I21 instructor answer, UC-S19 the asker's follow-up, and the author's own edit /
 * delete (D-40; state machine docs/ai/02 §2.2, enforced by {@link QuestionAnswer}). BR-38 / D-38: only the asker and
 * the course instructors reply ({@code @qaAccess.canReply} in front, re-checked here at send time, UC-I21 E1).
 * {@code author_type} is a snapshot taken now from the caller's relation to the course (instructor wins over student;
 * kept when the role is lost later, D-43). An instructor answer publishes {@link QuestionAnsweredByInstructor} inside
 * the transaction (D-42); a student follow-up publishes nothing. The question row is locked, so a reply and an admin
 * hide of the question are serialized.
 */
@Service
public class AnswerService {

    private static final Logger log = LoggerFactory.getLogger(AnswerService.class);

    private final CourseQuestionRepository questions;
    private final QuestionAnswerRepository answers;
    private final CourseAccessApi courseAccess;
    private final UserQueryApi users;
    private final QaMapper mapper;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @PersistenceContext
    private EntityManager em;

    public AnswerService(CourseQuestionRepository questions, QuestionAnswerRepository answers,
                         CourseAccessApi courseAccess, UserQueryApi users, QaMapper mapper,
                         ApplicationEventPublisher events, Clock clock) {
        this.questions = questions;
        this.answers = answers;
        this.courseAccess = courseAccess;
        this.users = users;
        this.mapper = mapper;
        this.events = events;
        this.clock = clock;
    }

    /**
     * UC-I21 / UC-S19: a new visible answer of the caller in a visible question. Neither a course instructor nor the
     * asker → 403 {@code QA_REPLY_NOT_ALLOWED}; hidden question → 409 {@code QA_CONTENT_HIDDEN_BY_ADMIN} (E2); deleted
     * → 404.
     */
    @Transactional
    public AnswerResponse post(Long userId, Long questionId, String content) {
        CourseQuestion question = questions.findByIdForUpdate(questionId).orElseThrow(BusinessException::notFound);
        if (question.getVisibilityStatus() == ContentStatus.DELETED) {
            throw BusinessException.notFound();
        }
        Long courseId = question.getEnrollment().getCourseId();
        Long askerId = question.getEnrollment().getStudentId();
        AuthorType type;
        if (courseAccess.isCourseInstructor(userId, courseId)) {
            type = AuthorType.INSTRUCTOR;
        } else if (askerId.equals(userId)) {
            type = AuthorType.STUDENT;
        } else {
            throw new BusinessException(ErrorCode.QA_REPLY_NOT_ALLOWED); // BR-38
        }
        QuestionAnswer answer = answers.save(QuestionAnswer.post(question, userId, type, content, clock.instant()));
        AnswerResponse response = flushAndRead(answer);
        if (type == AuthorType.INSTRUCTOR) {
            events.publishEvent(new QuestionAnsweredByInstructor(questionId, answer.getId(), courseId, askerId, userId,
                    question.getTitle()));
        }
        log.info("Answer posted: answerId={}, questionId={}, authorType={}, userId={}", answer.getId(), questionId,
                type.code(), userId);
        return response;
    }

    /** UC-I21 A1 / UC-S19: the author's own visible answer → new content. Hidden → 409 {@code QA_CONTENT_HIDDEN_BY_ADMIN}. */
    @Transactional
    public AnswerResponse edit(Long userId, Long answerId, String content) {
        QuestionAnswer answer = own(userId, answerId);
        answer.edit(content);
        AnswerResponse response = flushAndRead(answer);
        log.info("Answer edited: answerId={}, userId={}", answerId, userId);
        return response;
    }

    /** UC-I21 A1 / UC-S19: the author's own visible answer → deleted (final, D-40). */
    @Transactional
    public void delete(Long userId, Long answerId) {
        QuestionAnswer answer = own(userId, answerId);
        answer.delete();
        answers.flush();
        log.info("Answer deleted: answerId={}, userId={}", answerId, userId);
    }

    /** The answer locked, only if the caller wrote it and its question is not deleted (otherwise 404, NFR-01). */
    private QuestionAnswer own(Long userId, Long answerId) {
        QuestionAnswer answer = answers.findByIdForUpdate(answerId).orElseThrow(BusinessException::notFound);
        if (!answer.isAuthoredBy(userId) || answer.getVisibilityStatus() == ContentStatus.DELETED
                || answer.getQuestion().getVisibilityStatus() == ContentStatus.DELETED) {
            throw BusinessException.notFound();
        }
        return answer;
    }

    /** Flushes, reloads the DB-maintained {@code updated_at} and maps with the author's name. */
    private AnswerResponse flushAndRead(QuestionAnswer answer) {
        answers.flush();
        em.refresh(answer);
        UserSummary author = users.findSummaries(List.of(answer.getAuthorId())).get(answer.getAuthorId());
        return mapper.toAnswer(answer, author);
    }
}
