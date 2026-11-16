package com.edumy.authoring.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.edumy.authoring.dto.response.ChapterResponse;
import com.edumy.authoring.dto.response.InstructorCourseDetailResponse;
import com.edumy.authoring.api.EnrollmentCountPort;
import com.edumy.authoring.dto.response.InstructorCourseSummaryResponse;
import com.edumy.authoring.dto.response.LessonResponse;
import com.edumy.authoring.dto.response.PendingCourseResponse;
import com.edumy.authoring.dto.response.ReviewStatusResponse;
import com.edumy.authoring.entity.Chapter;
import com.edumy.authoring.entity.Course;
import com.edumy.authoring.entity.CourseCoInstructor;
import com.edumy.authoring.mapper.ChapterMapper;
import com.edumy.authoring.mapper.CourseMapper;
import com.edumy.authoring.mapper.LessonMapper;
import com.edumy.authoring.repository.ChapterRepository;
import com.edumy.authoring.repository.ChapterRepository.OutlineCountView;
import com.edumy.authoring.repository.CourseCoInstructorRepository;
import com.edumy.authoring.repository.CourseRepository;
import com.edumy.authoring.repository.CourseRepository.InstructorCourseView;
import com.edumy.authoring.repository.CourseRepository.PendingCourseView;
import com.edumy.authoring.repository.LessonRepository;
import com.edumy.identity.api.UserQueryApi;
import com.edumy.identity.api.UserSummary;
import com.edumy.shared.error.BusinessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Instructor and admin reads of courses (UC-I02, UC-I18, UC-A09). Access is decided before these methods run
 * ({@code @courseAccess} / admin role, docs/ai/04 §4.3); every method costs a fixed number of statements whatever the
 * number of rows (user names through one {@link UserQueryApi#findSummaries} call per response).
 */
@Service
@Transactional(readOnly = true)
public class CourseQueryService {

    static final String RELATION_MAIN = "main";
    static final String RELATION_CO = "co";

    private final CourseRepository courses;
    private final ChapterRepository chapters;
    private final LessonRepository lessons;
    private final CourseCoInstructorRepository coInstructors;
    private final UserQueryApi users;
    private final CourseMapper courseMapper;
    private final EnrollmentCountPort enrollmentCounts;
    private final ChapterMapper chapterMapper;
    private final LessonMapper lessonMapper;

    public CourseQueryService(CourseRepository courses, ChapterRepository chapters, LessonRepository lessons,
                              CourseCoInstructorRepository coInstructors, UserQueryApi users,
                              CourseMapper courseMapper, ChapterMapper chapterMapper, LessonMapper lessonMapper,
                              EnrollmentCountPort enrollmentCounts) {
        this.courses = courses;
        this.chapters = chapters;
        this.lessons = lessons;
        this.coInstructors = coInstructors;
        this.users = users;
        this.courseMapper = courseMapper;
        this.chapterMapper = chapterMapper;
        this.lessonMapper = lessonMapper;
        this.enrollmentCounts = enrollmentCounts;
    }

    /**
     * UC-I02: courses where {@code instructorId} is the main instructor or an accepted co-instructor (BR-03; pending,
     * declined and removed invitations do not count), any status, newest first (fixed order, client sort ignored).
     * Statements: page + count (skipped by Spring Data when the first page is not full) + outline counts of the page +
     * enrolled students of the page (UC-I02 "số học viên", P13, through {@code EnrollmentCountPort}).
     */
    public Page<InstructorCourseSummaryResponse> listMine(Long instructorId, Pageable pageable) {
        Page<InstructorCourseView> page = courses.findInstructorCourses(instructorId, null, unsorted(pageable));
        List<Long> ids = page.map(InstructorCourseView::getId).getContent();
        Map<Long, OutlineCountView> counts = page.isEmpty() ? Map.of()
                : chapters.countOutlineByCourseIdIn(ids).stream()
                        .collect(Collectors.toMap(OutlineCountView::getCourseId, Function.identity()));
        Map<Long, Long> students = enrollmentCounts.countStudents(ids); // P13, port implemented by learning
        return page.map(course -> {
            OutlineCountView count = counts.get(course.getId());
            return courseMapper.toSummary(course, relation(course.getMainInstructorId(), instructorId),
                    count == null ? 0 : count.getChapterCount(), count == null ? 0 : count.getLessonCount(),
                    students.getOrDefault(course.getId(), 0L));
        });
    }

    /**
     * UC-I02 detail / editor: the course with chapters → lessons (with content), category, main instructor and every
     * co-instructor invitation. The caller was already checked as a course instructor
     * ({@code @courseAccess.isInstructor}). Statements: course + category, chapters, lessons, co-instructors, names.
     */
    public InstructorCourseDetailResponse detail(Long courseId, Long callerId) {
        Course course = courses.findWithCategoryById(courseId).orElseThrow(BusinessException::notFound);
        List<ChapterResponse> outline = outline(courseId);

        List<CourseCoInstructor> coRows = coInstructors.findByCourseIdOrderByInvitedAtAscIdAsc(courseId);
        List<Long> userIds = new ArrayList<>();
        userIds.add(course.getMainInstructorId());
        coRows.forEach(row -> userIds.add(row.getInstructorId()));
        Map<Long, UserSummary> names = users.findSummaries(userIds);

        List<InstructorCourseDetailResponse.CoInstructor> coList = coRows.stream()
                .map(row -> courseMapper.toCoInstructor(row, names.get(row.getInstructorId())))
                .toList();
        return courseMapper.toDetail(course, relation(course.getMainInstructorId(), callerId),
                names.get(course.getMainInstructorId()), coList, outline);
    }

    /**
     * The invitation of {@code instructorId} to the course as shown in {@link #detail}'s {@code coInstructors} (answer
     * of UC-I13). Two statements (row, name).
     */
    public InstructorCourseDetailResponse.CoInstructor coInstructor(Long courseId, Long instructorId) {
        CourseCoInstructor row = coInstructors.findByCourseIdAndInstructorId(courseId, instructorId)
                .orElseThrow(BusinessException::notFound);
        return courseMapper.toCoInstructor(row, users.findSummary(instructorId).orElse(null));
    }

    /**
     * Chapters of the course in order, each with its lessons (with content) in order — the editor outline of
     * {@link #detail} and the answer of UC-I08. Two statements (chapters, lessons with their chapter).
     */
    public List<ChapterResponse> outline(Long courseId) {
        List<Chapter> chapterRows = chapters.findByCourseIdOrderBySortOrderAsc(courseId);
        Map<Long, List<LessonResponse>> lessonsByChapter = lessons.findByCourseIdOrdered(courseId).stream()
                .collect(Collectors.groupingBy(lesson -> lesson.getChapter().getId(),
                        Collectors.mapping(lessonMapper::toResponse, Collectors.toList())));
        return chapterRows.stream()
                .map(chapter -> chapterMapper.toResponse(chapter,
                        lessonsByChapter.getOrDefault(chapter.getId(), List.of())))
                .toList();
    }

    /** One chapter with its lessons in order (answer of UC-I05, I06, I12). Two statements. */
    public ChapterResponse chapter(Long chapterId) {
        Chapter chapter = chapters.findById(chapterId).orElseThrow(BusinessException::notFound);
        return chapterMapper.toResponse(chapter, lessons.findByChapterIdOrderBySortOrderAsc(chapterId).stream()
                .map(lessonMapper::toResponse)
                .toList());
    }

    /** One lesson with its content (answer of UC-I09, I10). One statement. */
    public LessonResponse lesson(Long lessonId) {
        return lessons.findById(lessonId).map(lessonMapper::toResponse).orElseThrow(BusinessException::notFound);
    }

    /** UC-I18: status, last submission, last decision and the latest rejection / hiding reason. One statement. */
    public ReviewStatusResponse reviewStatus(Long courseId) {
        return courses.findById(courseId).map(courseMapper::toReviewStatus).orElseThrow(BusinessException::notFound);
    }

    /**
     * UC-A09: {@code pending_review} courses, the oldest submission first (UC-I17 step 3), with category and main
     * instructor. Statements: page + count (when the first page is full) + instructor names of the page.
     */
    public Page<PendingCourseResponse> pendingQueue(Pageable pageable) {
        Page<PendingCourseView> page = courses.findPendingQueue(unsorted(pageable));
        Map<Long, UserSummary> names = users.findSummaries(
                page.map(PendingCourseView::getMainInstructorId).getContent());
        return page.map(course -> courseMapper.toPending(course, names.get(course.getMainInstructorId())));
    }

    private static String relation(Long mainInstructorId, Long userId) {
        return mainInstructorId.equals(userId) ? RELATION_MAIN : RELATION_CO;
    }

    /** The order of these lists is fixed by their query; a client {@code sort} is ignored. */
    private static Pageable unsorted(Pageable pageable) {
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
    }
}
