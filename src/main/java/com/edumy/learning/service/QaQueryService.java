package com.edumy.learning.service;

import com.edumy.authoring.api.CourseAccessApi;
import com.edumy.authoring.api.CourseQueryApi;
import com.edumy.authoring.api.CourseSummary;
import com.edumy.identity.api.RoleQueryApi;
import com.edumy.identity.api.UserQueryApi;
import com.edumy.identity.api.UserSummary;
import com.edumy.learning.dto.request.CourseQuestionSearchRequest;
import com.edumy.learning.dto.request.InstructorQuestionSearchRequest;
import com.edumy.learning.dto.response.AnswerResponse;
import com.edumy.learning.dto.response.QuestionSummaryResponse;
import com.edumy.learning.dto.response.QuestionThreadResponse;
import com.edumy.learning.entity.AuthorType;
import com.edumy.learning.entity.ContentStatus;
import com.edumy.learning.entity.CourseQuestion;
import com.edumy.learning.entity.QuestionAnswer;
import com.edumy.learning.mapper.QaMapper;
import com.edumy.learning.repository.CourseQuestionRepository;
import com.edumy.learning.repository.CourseQuestionRepository.QuestionSummaryView;
import com.edumy.learning.repository.QuestionAnswerRepository;
import com.edumy.shared.error.BusinessException;
import com.edumy.shared.security.RoleCode;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Q&amp;A reads (UC-S18 course list and thread, UC-I22 instructor list). Who may call is decided before by
 * {@code @enrollmentAccess.canViewContent} / {@code @qaAccess.canView} / {@code @enrollmentAccess.isActiveInstructor};
 * this service only shapes what each caller sees: deleted content never; hidden questions for their asker and an
 * admin; hidden answers for their author and an admin (BR-41). Statements: course list = page + count + names;
 * thread = question + answers + names; instructor list = instructed ids + page + count + names + courses.
 */
@Service
@Transactional(readOnly = true)
public class QaQueryService {

    private static final char LIKE_ESCAPE = '!';

    private final CourseQuestionRepository questions;
    private final QuestionAnswerRepository answers;
    private final CourseAccessApi courseAccess;
    private final CourseQueryApi courses;
    private final UserQueryApi users;
    private final RoleQueryApi roles;
    private final QaMapper mapper;

    public QaQueryService(CourseQuestionRepository questions, QuestionAnswerRepository answers,
                          CourseAccessApi courseAccess, CourseQueryApi courses, UserQueryApi users, RoleQueryApi roles,
                          QaMapper mapper) {
        this.questions = questions;
        this.answers = answers;
        this.courseAccess = courseAccess;
        this.courses = courses;
        this.users = users;
        this.roles = roles;
        this.mapper = mapper;
    }

    /** UC-S18: questions of a course, newest first; the caller may view the course content. */
    public Page<QuestionSummaryResponse> listForCourse(Long userId, Long courseId, CourseQuestionSearchRequest filter,
                                                       Pageable pageable) {
        boolean admin = roles.hasActiveRole(userId, RoleCode.ADMIN);
        Page<QuestionSummaryView> page = questions.search(courseId, filter.lessonId(),
                filter.mineOnly() ? userId : null, userId, admin, filter.unansweredOnly(), likePattern(filter.q()),
                unsorted(pageable));
        if (page.isEmpty()) {
            return page.map(q -> null);
        }
        Map<Long, UserSummary> names = users.findSummaries(page.stream().map(QuestionSummaryView::getAskerId).toList());
        return page.map(q -> mapper.toSummary(q, names.get(q.getAskerId()), null));
    }

    /** UC-S18: the thread of a question the caller may see (access bean). */
    public QuestionThreadResponse thread(Long userId, Long questionId) {
        CourseQuestion question = questions.findWithEnrollmentById(questionId).orElseThrow(BusinessException::notFound);
        boolean admin = roles.hasActiveRole(userId, RoleCode.ADMIN);
        List<QuestionAnswer> all = answers.findThread(questionId, true);
        boolean instructorReplied = all.stream().anyMatch(a -> a.getAuthorType() == AuthorType.INSTRUCTOR
                && a.getVisibilityStatus() == ContentStatus.VISIBLE);
        List<QuestionAnswer> shown = all.stream()
                .filter(a -> a.getVisibilityStatus() == ContentStatus.VISIBLE || admin
                        || a.getAuthorId().equals(userId))
                .toList();
        Set<Long> ids = new HashSet<>(shown.stream().map(QuestionAnswer::getAuthorId).toList());
        ids.add(question.getEnrollment().getStudentId());
        Map<Long, UserSummary> names = users.findSummaries(ids);
        List<AnswerResponse> responses = shown.stream()
                .map(a -> mapper.toAnswer(a, names.get(a.getAuthorId())))
                .toList();
        return mapper.toThread(question, names.get(question.getEnrollment().getStudentId()), responses,
                instructorReplied);
    }

    /** UC-I22: visible questions of the courses the caller instructs (optionally one course, optionally unanswered). */
    public Page<QuestionSummaryResponse> listForInstructor(Long userId, InstructorQuestionSearchRequest filter,
                                                           Pageable pageable) {
        Set<Long> courseIds = courseAccess.findInstructedCourseIds(userId);
        if (filter.courseId() != null) {
            if (!courseIds.contains(filter.courseId())) {
                throw BusinessException.notFound(); // NFR-01: not instructed = unknown
            }
            courseIds = Set.of(filter.courseId());
        }
        Pageable fixed = unsorted(pageable);
        if (courseIds.isEmpty()) {
            return Page.empty(fixed);
        }
        Page<QuestionSummaryView> page = questions.findForInstructor(courseIds, filter.unansweredOnly(), fixed);
        if (page.isEmpty()) {
            return page.map(q -> null);
        }
        Map<Long, UserSummary> names = users.findSummaries(page.stream().map(QuestionSummaryView::getAskerId).toList());
        Map<Long, CourseSummary> titles = courses
                .findSummaries(page.stream().map(QuestionSummaryView::getCourseId).toList());
        return page.map(q -> mapper.toSummary(q, names.get(q.getAskerId()),
                titles.get(q.getCourseId()) == null ? null : titles.get(q.getCourseId()).title()));
    }

    /** {@code %keyword%} with {@code % _ !} escaped; {@code %} when there is no keyword. */
    private static String likePattern(String keyword) {
        if (keyword == null) {
            return "%";
        }
        String e = String.valueOf(LIKE_ESCAPE);
        return "%" + keyword.replace(e, e + e).replace("%", e + "%").replace("_", e + "_") + "%";
    }

    private static Pageable unsorted(Pageable pageable) {
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
    }
}
