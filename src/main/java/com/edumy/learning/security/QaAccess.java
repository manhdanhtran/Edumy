package com.edumy.learning.security;

import com.edumy.authoring.api.CourseAccessApi;
import com.edumy.identity.api.RoleQueryApi;
import com.edumy.learning.entity.ContentStatus;
import com.edumy.learning.repository.CourseQuestionRepository;
import com.edumy.learning.repository.CourseQuestionRepository.QuestionAccessView;
import com.edumy.learning.repository.QuestionAnswerRepository;
import com.edumy.shared.error.BusinessException;
import com.edumy.shared.error.ErrorCode;
import com.edumy.shared.security.RoleCode;
import org.springframework.stereotype.Component;

/**
 * {@code @qaAccess} for {@code @PreAuthorize} on Q&amp;A endpoints (UC-S17…S19, UC-I21). A question the caller cannot
 * see answers {@code RESOURCE_NOT_FOUND} (404, NFR-01), exactly like an unknown id:
 * <ul>
 *   <li>{@code deleted}: nobody sees it (the asker removed the whole thread);</li>
 *   <li>{@code hidden}: only its asker and an admin (UC-A19; the asker still sees it among "my questions");</li>
 *   <li>{@code visible}: whoever can view the course content (enrolled, course instructor, admin: BR-37, D-32).</li>
 * </ul>
 * State conflicts (answering or editing hidden content, BR-41) are left to the services (409).
 */
@Component("qaAccess")
public class QaAccess {

    private final CourseQuestionRepository questions;
    private final QuestionAnswerRepository answers;
    private final CourseAccessApi courseAccess;
    private final RoleQueryApi roles;
    private final EnrollmentAccess enrollmentAccess;

    public QaAccess(CourseQuestionRepository questions, QuestionAnswerRepository answers, CourseAccessApi courseAccess,
                    RoleQueryApi roles, EnrollmentAccess enrollmentAccess) {
        this.questions = questions;
        this.answers = answers;
        this.courseAccess = courseAccess;
        this.roles = roles;
        this.enrollmentAccess = enrollmentAccess;
    }

    /**
     * BR-38 / D-38: only the asker and the course instructors reply. A caller who can see the question but is
     * neither gets {@code QA_REPLY_NOT_ALLOWED} (403); a caller who cannot see it gets 404.
     */
    public boolean canReply(Long questionId) {
        Long userId = EnrollmentAccess.callerId();
        QuestionAccessView question = seenQuestion(questionId, userId);
        Long courseId = question.getCourseId();
        if (question.getAskerId().equals(userId) || courseAccess.isCourseInstructor(userId, courseId)) {
            return true;
        }
        // a hidden question got this far only for an admin, who can see it
        if (question.getVisibilityStatus() == ContentStatus.HIDDEN || enrollmentAccess.canView(userId, courseId)) {
            throw new BusinessException(ErrorCode.QA_REPLY_NOT_ALLOWED); // BR-38: other students do not answer
        }
        throw BusinessException.notFound();
    }

    /** The caller asked the question (UC-S19 edit / delete / resolve). A deleted question is gone (404). */
    public boolean isQuestionAuthor(Long questionId) {
        Long userId = EnrollmentAccess.callerId();
        boolean author = questionId != null && questions.findAccessViewById(questionId)
                .filter(q -> q.getVisibilityStatus() != ContentStatus.DELETED)
                .filter(q -> q.getAskerId().equals(userId))
                .isPresent();
        if (!author) {
            throw BusinessException.notFound();
        }
        return true;
    }

    /** The caller wrote the answer (edit / delete, D-40). Deleted answers and answers of deleted questions → 404. */
    public boolean isAnswerAuthor(Long answerId) {
        Long userId = EnrollmentAccess.callerId();
        if (answerId == null || !answers.existsNotDeletedByIdAndAuthorId(answerId, userId)) {
            throw BusinessException.notFound();
        }
        return true;
    }

    /** The question unless it is unknown, deleted, or hidden from this caller (all → 404). */
    private QuestionAccessView seenQuestion(Long questionId, Long userId) {
        QuestionAccessView question = questionId == null ? null
                : questions.findAccessViewById(questionId).orElse(null);
        if (question == null || question.getVisibilityStatus() == ContentStatus.DELETED) {
            throw BusinessException.notFound();
        }
        if (question.getVisibilityStatus() == ContentStatus.HIDDEN && !question.getAskerId().equals(userId)
                && !roles.hasActiveRole(userId, RoleCode.ADMIN)) {
            throw BusinessException.notFound();
        }
        return question;
    }
}
