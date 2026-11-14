package com.edumy.learning.service;

import com.edumy.authoring.api.ChapterOutline;
import com.edumy.authoring.api.CourseQueryApi;
import com.edumy.authoring.api.CourseSummary;
import com.edumy.authoring.api.LessonContent;
import com.edumy.authoring.api.LessonQueryApi;
import com.edumy.identity.api.UserQueryApi;
import com.edumy.identity.api.UserSummary;
import com.edumy.learning.dto.response.EnrollmentResponse;
import com.edumy.learning.dto.response.LearningOutlineResponse;
import com.edumy.learning.dto.response.LessonContentResponse;
import com.edumy.learning.dto.response.StudentProgressResponse;
import com.edumy.learning.entity.Enrollment;
import com.edumy.learning.entity.LessonProgress;
import com.edumy.learning.mapper.EnrollmentMapper;
import com.edumy.learning.repository.CertificateRepository;
import com.edumy.learning.repository.CertificateRepository.EnrollmentCertificateView;
import com.edumy.learning.repository.EnrollmentRepository;
import com.edumy.learning.repository.EnrollmentRepository.CourseStudentView;
import com.edumy.learning.repository.EnrollmentRepository.StudentEnrollmentView;
import com.edumy.learning.repository.LessonProgressRepository;
import com.edumy.shared.error.BusinessException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Learning reads: my enrollments (UC-S08), the course outline with progress (UC-S11), lesson content (UC-S09) and the
 * students of a course (UC-I19). Access is decided before by {@code @enrollmentAccess} / {@code @courseAccess}.
 * Progress is derived on every read (D-33: floor(completed * 100 / total lessons now)). Statements: my enrollments =
 * page + count + courses + lesson counts + certificates (2 when the page is empty); outline =
 * enrollment + course + chapters + lessons + completed ids; students = page + count + lesson count + names.
 */
@Service
@Transactional(readOnly = true)
public class EnrollmentQueryService {

    private final EnrollmentRepository enrollments;
    private final LessonProgressRepository progresses;
    private final CertificateRepository certificates;
    private final CourseQueryApi courses;
    private final LessonQueryApi lessons;
    private final UserQueryApi users;
    private final EnrollmentMapper mapper;

    public EnrollmentQueryService(EnrollmentRepository enrollments, LessonProgressRepository progresses,
                                  CertificateRepository certificates, CourseQueryApi courses, LessonQueryApi lessons,
                                  UserQueryApi users, EnrollmentMapper mapper) {
        this.enrollments = enrollments;
        this.progresses = progresses;
        this.certificates = certificates;
        this.courses = courses;
        this.lessons = lessons;
        this.users = users;
        this.mapper = mapper;
    }

    /** UC-S08: the caller's enrollments, newest first (hidden courses included, BR-31); no N+1. */
    public Page<EnrollmentResponse> listMine(Long studentId, Pageable pageable) {
        Page<StudentEnrollmentView> page = enrollments.findSummariesByStudentId(studentId, unsorted(pageable));
        if (page.isEmpty()) {
            return page.map(e -> null);
        }
        Set<Long> courseIds = page.stream().map(StudentEnrollmentView::getCourseId).collect(Collectors.toSet());
        Map<Long, CourseSummary> summaries = courses.findSummaries(courseIds);
        Map<Long, Integer> totals = lessons.countLessons(courseIds);
        Map<Long, String> codes = certificates
                .findByEnrollmentIdIn(page.stream().map(StudentEnrollmentView::getId).toList()).stream()
                .collect(Collectors.toMap(EnrollmentCertificateView::getEnrollmentId,
                        EnrollmentCertificateView::getCertificateCode));
        return page.map(e -> mapper.toEnrollment(e, summaries.get(e.getCourseId()),
                totals.getOrDefault(e.getCourseId(), 0), codes.get(e.getId())));
    }


    /** UC-S15 response: the caller's enrollment in {@code courseId} (the same row as UC-S08), after the write. */
    public EnrollmentResponse findMine(Long studentId, Long courseId) {
        StudentEnrollmentView view = enrollments.findSummaryByStudentIdAndCourseId(studentId, courseId)
                .orElseThrow(BusinessException::notFound);
        CourseSummary course = courses.findSummaries(Set.of(courseId)).get(courseId);
        int total = lessons.countLessons(Set.of(courseId)).getOrDefault(courseId, 0);
        String code = certificates.findByEnrollmentIdIn(List.of(view.getId())).stream()
                .map(EnrollmentCertificateView::getCertificateCode).findFirst().orElse(null);
        return mapper.toEnrollment(view, course, total, code);
    }
    /**
     * UC-S11 / UC-S09 preview of the whole course: the caller may view the content (checked by the access bean).
     * Instructors and admins get the outline without progress (D-32).
     */
    public LearningOutlineResponse outline(Long userId, Long courseId) {
        CourseSummary course = courses.findSummaries(Set.of(courseId)).get(courseId);
        if (course == null) {
            throw BusinessException.notFound();
        }
        List<ChapterOutline> chapters = lessons.findOutline(courseId);
        Set<Long> completed = enrollments.findByStudentIdAndCourseId(userId, courseId)
                .map(e -> (Set<Long>) new HashSet<>(progresses.findCompletedLessonIds(e.getId())))
                .orElse(null);
        return mapper.toOutline(courseId, course.title(), chapters, completed);
    }

    /** UC-S09: the lesson content; no visit is recorded here (D-34: that is {@code POST .../visit}). */
    public LessonContentResponse lesson(Long userId, Long lessonId) {
        LessonContent content = lessons.findContent(lessonId).orElseThrow(BusinessException::notFound);
        Boolean completed = enrollments.findByStudentIdAndCourseId(userId, content.courseId())
                .map(Enrollment::getId)
                .map(id -> progresses.findByEnrollmentIdAndLessonId(id, lessonId)
                        .map(LessonProgress::isCompleted).orElse(false))
                .orElse(null);
        return mapper.toLessonContent(content, completed);
    }

    /** UC-I19: students of the course, newest enrollment first, with progress; the caller is its instructor. */
    public Page<StudentProgressResponse> listStudents(Long courseId, Pageable pageable) {
        Page<CourseStudentView> page = enrollments.findStudentsByCourseId(courseId, unsorted(pageable));
        if (page.isEmpty()) {
            return page.map(e -> null);
        }
        Map<Long, UserSummary> names = users
                .findSummaries(page.stream().map(CourseStudentView::getStudentId).toList());
        int total = lessons.countLessons(Set.of(courseId)).getOrDefault(courseId, 0);
        return page.map(e -> mapper.toStudent(e, names.get(e.getStudentId()), total));
    }

    private static Pageable unsorted(Pageable pageable) {
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
    }
}
