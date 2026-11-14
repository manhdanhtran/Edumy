package com.edumy.learning.service;

import java.time.Clock;

import com.edumy.authoring.api.LessonQueryApi;
import com.edumy.learning.dto.response.QuestionThreadResponse;
import com.edumy.learning.entity.ContentStatus;
import com.edumy.learning.entity.CourseQuestion;
import com.edumy.learning.entity.Enrollment;
import com.edumy.learning.repository.CourseQuestionRepository;
import com.edumy.learning.repository.EnrollmentRepository;
import com.edumy.shared.error.BusinessException;
import com.edumy.shared.error.ErrorCode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Student side of the course Q&amp;A: UC-S17 ask, UC-S19 edit / resolve / delete the caller's own question (state
 * machine docs/ai/02 §2.2 / SRS §3.4.6, enforced by {@link CourseQuestion}). The asker id always comes from the JWT; a
 * question is only reachable through the caller's own enrollment (NFR-01, {@code @enrollmentAccess} /
 * {@code @qaAccess} in front, re-checked here). Writes lock the question row, so an asker edit and an admin hide are
 * serialized. Responses are the thread as the asker sees it ({@link QaQueryService#thread}).
 */
@Service
public class QuestionService {

    private static final Logger log = LoggerFactory.getLogger(QuestionService.class);

    private final CourseQuestionRepository questions;
    private final EnrollmentRepository enrollments;
    private final LessonQueryApi lessons;
    private final QaQueryService queries;
    private final Clock clock;

    @PersistenceContext
    private EntityManager em;

    public QuestionService(CourseQuestionRepository questions, EnrollmentRepository enrollments,
                           LessonQueryApi lessons, QaQueryService queries, Clock clock) {
        this.questions = questions;
        this.enrollments = enrollments;
        this.lessons = lessons;
        this.queries = queries;
        this.clock = clock;
    }

    /**
     * UC-S17: a new visible, unresolved question of the caller's enrollment. No enrollment → 404 (E1, same as an
     * unknown course); a lesson that is not a lesson of this course (or does not exist) → 422
     * {@code LESSON_COURSE_MISMATCH} (BR-37; trigger {@code trg_course_questions_lesson_course} is the backstop).
     */
    @Transactional
    public QuestionThreadResponse ask(Long studentId, Long courseId, Long lessonId, String title, String content) {
        Enrollment enrollment = enrollments.findByStudentIdAndCourseId(studentId, courseId)
                .orElseThrow(BusinessException::notFound);
        if (lessonId != null && !courseId.equals(lessons.findCourseId(lessonId).orElse(null))) {
            throw new BusinessException(ErrorCode.LESSON_COURSE_MISMATCH);
        }
        CourseQuestion question = questions.save(CourseQuestion.ask(enrollment, lessonId, title, content,
                clock.instant()));
        QuestionThreadResponse response = flushAndRead(studentId, question);
        log.info("Question asked: questionId={}, courseId={}, userId={}", question.getId(), courseId, studentId);
        return response;
    }

    /** UC-S19: own visible question → new title and content. Hidden → 409 {@code QA_CONTENT_HIDDEN_BY_ADMIN} (BR-41). */
    @Transactional
    public QuestionThreadResponse edit(Long studentId, Long questionId, String title, String content) {
        CourseQuestion question = own(studentId, questionId);
        question.edit(title, content);
        QuestionThreadResponse response = flushAndRead(studentId, question);
        log.info("Question edited: questionId={}, userId={}", questionId, studentId);
        return response;
    }

    /** UC-S19 / D-39: mark (or, {@code resolved = false}, unmark) the own visible question as resolved. */
    @Transactional
    public QuestionThreadResponse resolve(Long studentId, Long questionId, boolean resolved) {
        CourseQuestion question = own(studentId, questionId);
        if (resolved) {
            question.markResolved();
        } else {
            question.markUnresolved();
        }
        QuestionThreadResponse response = flushAndRead(studentId, question);
        log.info("Question {}: questionId={}, userId={}", resolved ? "resolved" : "reopened", questionId, studentId);
        return response;
    }

    /** UC-S19: own visible question → deleted (final); the whole thread disappears (SRS §3.4.6). */
    @Transactional
    public void delete(Long studentId, Long questionId) {
        CourseQuestion question = own(studentId, questionId);
        question.delete();
        questions.flush();
        log.info("Question deleted: questionId={}, userId={}", questionId, studentId);
    }

    /** The question locked, only if the caller asked it and it is not deleted (otherwise 404, NFR-01). */
    private CourseQuestion own(Long studentId, Long questionId) {
        CourseQuestion question = questions.findByIdForUpdate(questionId).orElseThrow(BusinessException::notFound);
        if (!question.isAskedBy(studentId)
                || question.getVisibilityStatus() == ContentStatus.DELETED) {
            throw BusinessException.notFound();
        }
        return question;
    }

    /** Flushes, reloads the DB-maintained {@code updated_at} and reads the thread. */
    private QuestionThreadResponse flushAndRead(Long userId, CourseQuestion question) {
        questions.flush();
        em.refresh(question);
        return queries.thread(userId, question.getId());
    }
}
