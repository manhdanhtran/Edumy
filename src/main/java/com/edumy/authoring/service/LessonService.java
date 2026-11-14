package com.edumy.authoring.service;

import java.time.Clock;
import java.util.List;

import com.edumy.authoring.api.CourseContentChanged;
import com.edumy.authoring.entity.Chapter;
import com.edumy.authoring.entity.Course;
import com.edumy.authoring.entity.Lesson;
import com.edumy.authoring.entity.LessonType;
import com.edumy.authoring.repository.ChapterRepository;
import com.edumy.authoring.repository.CourseRepository;
import com.edumy.authoring.repository.LessonRepository;
import com.edumy.shared.error.BusinessException;
import com.edumy.shared.error.ErrorCode;
import com.edumy.shared.jpa.CodeEnum;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lesson writes of a course instructor (main or accepted co-instructor, BR-04): UC-I09 create, UC-I10 update, UC-I11
 * delete, UC-I12 reorder. Access was decided by {@code @chapterAccess} / {@code @lessonAccess} before. Like
 * {@link ChapterService}, every method first locks the course row (serializes the content writes of the course,
 * re-checks BR-33), keeps positions 1..n and publishes {@link CourseContentChanged} for a published / hidden course
 * (D-14). The content must match the type (D-22): the entity rejects the other type's field with 400
 * {@code VALIDATION_FAILED}, so ck_lessons_content_match is never reached.
 */
@Service
public class LessonService {

    private final CourseRepository courses;
    private final ChapterRepository chapters;
    private final LessonRepository lessons;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public LessonService(CourseRepository courses, ChapterRepository chapters, LessonRepository lessons,
                         ApplicationEventPublisher events, Clock clock) {
        this.courses = courses;
        this.chapters = chapters;
        this.lessons = lessons;
        this.events = events;
        this.clock = clock;
    }

    /** UC-I09: new lesson at the end of the chapter ({@code sort_order} = max + 1 under the course lock). Its id. */
    @Transactional
    public Long create(Long chapterId, CreateLessonCommand command) {
        Course course = lockEditableCourse(courseIdOfChapter(chapterId));
        Chapter chapter = chapters.findById(chapterId).orElseThrow(BusinessException::notFound);
        int position = lessons.findMaxSortOrderByChapterId(chapterId) + 1;
        Lesson lesson = Lesson.create(chapter, command.title(), type(command.contentType()), command.videoUrl(),
                command.articleContent(), command.durationSeconds(), command.preview(), position, clock.instant());
        lessons.saveAndFlush(lesson);
        contentChanged(course);
        return lesson.getId();
    }

    /**
     * UC-I10 with PATCH semantics ("Lưu tiêu đề, nội dung, thời lượng, cờ xem trước"; effective at once on a published
     * course, D-14). A type change drops the content of the old type unless it is sent (then 400, D-22).
     */
    @Transactional
    public void update(Long lessonId, UpdateLessonCommand command) {
        Course course = lockEditableCourse(courseIdOfLesson(lessonId));
        Lesson lesson = lessons.findById(lessonId).orElseThrow(BusinessException::notFound);
        LessonType newType = command.contentType() != null ? type(command.contentType()) : lesson.getContentType();
        boolean typeChanged = newType != lesson.getContentType();
        lesson.update(
                command.title() != null ? command.title() : lesson.getTitle(),
                newType,
                command.videoUrl() != null ? command.videoUrl() : typeChanged ? null : lesson.getVideoUrl(),
                command.articleContent() != null ? command.articleContent()
                        : typeChanged ? null : lesson.getArticleContent(),
                command.durationSeconds() != null ? command.durationSeconds() : lesson.getDurationSeconds(),
                command.preview() != null ? command.preview() : lesson.isPreview());
        lessons.flush();
        contentChanged(course);
    }

    /**
     * UC-I11: the lesson goes with its progress (ON DELETE CASCADE); questions about it become course-level questions
     * (course_questions.lesson_id SET NULL); percentages are derived (D-33). E: the only lesson of a published / hidden
     * course → 409 {@code COURSE_LAST_LESSON_DELETE_FORBIDDEN} (D-14). The following lessons move up (1..n).
     */
    @Transactional
    public void delete(Long lessonId) {
        Course course = lockEditableCourse(courseIdOfLesson(lessonId));
        Lesson lesson = lessons.findWithChapterById(lessonId).orElseThrow(BusinessException::notFound);
        if (course.isPublishedOrHidden() && lessons.countByCourseId(course.getId()) <= 1) {
            throw new BusinessException(ErrorCode.COURSE_LAST_LESSON_DELETE_FORBIDDEN); // D-14
        }
        Long chapterId = lesson.getChapter().getId();
        int position = lesson.getSortOrder();
        lessons.delete(lesson);
        lessons.closeGap(chapterId, position);
        contentChanged(course);
    }

    /**
     * UC-I12, docs/ai/03 §3.4: lock the chapter's lessons, the submitted ids must be exactly those lessons (else 400
     * {@code VALIDATION_FAILED} on {@code ids}), phase 1 offset, phase 2 positions 1..n.
     */
    @Transactional
    public void reorder(Long chapterId, List<Long> ids) {
        Course course = lockEditableCourse(courseIdOfChapter(chapterId));
        ChapterService.requirePermutation(lessons.lockIdsByChapterId(chapterId), ids, "bài học", "chương");
        lessons.shiftSortOrders(chapterId);
        for (int i = 0; i < ids.size(); i++) {
            lessons.updateSortOrder(ids.get(i), i + 1);
        }
        contentChanged(course);
    }

    private Long courseIdOfChapter(Long chapterId) {
        return chapters.findCourseIdById(chapterId).orElseThrow(BusinessException::notFound);
    }

    private Long courseIdOfLesson(Long lessonId) {
        return lessons.findCourseIdById(lessonId).orElseThrow(BusinessException::notFound);
    }

    private Course lockEditableCourse(Long courseId) {
        Course course = courses.findByIdForUpdate(courseId).orElseThrow(BusinessException::notFound);
        course.assertEditable(); // BR-33: re-checked under the lock (a submit may have run since the access check)
        return course;
    }

    private void contentChanged(Course course) {
        if (course.isPublishedOrHidden()) {
            events.publishEvent(new CourseContentChanged(course.getId(), course.getSlug())); // D-14
        }
    }

    private static LessonType type(String code) {
        return CodeEnum.fromCode(LessonType.class, code);
    }
}
