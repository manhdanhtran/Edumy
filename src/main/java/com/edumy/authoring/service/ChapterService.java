package com.edumy.authoring.service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.edumy.authoring.api.CourseContentChanged;
import com.edumy.authoring.entity.Chapter;
import com.edumy.authoring.entity.Course;
import com.edumy.authoring.repository.ChapterRepository;
import com.edumy.authoring.repository.CourseRepository;
import com.edumy.authoring.repository.LessonRepository;
import com.edumy.shared.error.BusinessException;
import com.edumy.shared.error.ErrorCode;
import com.edumy.shared.error.FieldViolation;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Chapter writes of a course instructor (main or accepted co-instructor, BR-04): UC-I05 create, UC-I06 rename,
 * UC-I07 delete, UC-I08 reorder. Who may call was decided by {@code @courseAccess} / {@code @chapterAccess} before
 * (BR-26 role, relation, BR-33). Every method first locks the course row ({@code SELECT … FOR UPDATE}): it serializes
 * all content writes of the course (append position max + 1, the D-14 "≥1 lesson" count, reorders) and re-checks BR-33
 * against a concurrent submit. Positions stay 1..n: a delete closes the gap. A change on a published / hidden course
 * publishes {@link CourseContentChanged} (D-14, catalog outline cache).
 */
@Service
public class ChapterService {

    static final String IDS_FIELD = "ids";

    private final CourseRepository courses;
    private final ChapterRepository chapters;
    private final LessonRepository lessons;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public ChapterService(CourseRepository courses, ChapterRepository chapters, LessonRepository lessons,
                          ApplicationEventPublisher events, Clock clock) {
        this.courses = courses;
        this.chapters = chapters;
        this.lessons = lessons;
        this.events = events;
        this.clock = clock;
    }

    /** UC-I05: new chapter at the end of the course ({@code sort_order} = max + 1 under the course lock). Its id. */
    @Transactional
    public Long create(Long courseId, String title) {
        Course course = lockEditableCourse(courseId);
        int position = chapters.findMaxSortOrderByCourseId(courseId) + 1;
        Chapter chapter = chapters.saveAndFlush(Chapter.create(course, title, position, clock.instant()));
        contentChanged(course);
        return chapter.getId();
    }

    /** UC-I06: "Lưu tên chương". */
    @Transactional
    public void rename(Long chapterId, String title) {
        Course course = lockEditableCourse(courseIdOfChapter(chapterId));
        Chapter chapter = chapters.findById(chapterId).orElseThrow(BusinessException::notFound);
        chapter.rename(title);
        chapters.flush();
        contentChanged(course);
    }

    /**
     * UC-I07: the chapter goes with its lessons, their progress (ON DELETE CASCADE) and the questions about them become
     * course-level questions (SET NULL); completion percentages are derived, so they are "recomputed" by themselves
     * (D-33). E: on a published / hidden course the deletion would leave no lesson → 409
     * {@code COURSE_LAST_LESSON_DELETE_FORBIDDEN} (D-14). The following chapters move up (positions stay 1..n).
     */
    @Transactional
    public void delete(Long chapterId) {
        Course course = lockEditableCourse(courseIdOfChapter(chapterId));
        Chapter chapter = chapters.findById(chapterId).orElseThrow(BusinessException::notFound);
        if (course.isPublishedOrHidden()) {
            long inChapter = lessons.countByChapterId(chapterId);
            if (inChapter > 0 && lessons.countByCourseId(course.getId()) == inChapter) {
                throw new BusinessException(ErrorCode.COURSE_LAST_LESSON_DELETE_FORBIDDEN); // D-14
            }
        }
        int position = chapter.getSortOrder();
        chapters.delete(chapter);
        chapters.closeGap(course.getId(), position);
        contentChanged(course);
    }

    /**
     * UC-I08, docs/ai/03 §3.4: lock the chapters, the submitted ids must be exactly the course's chapters (else 400
     * {@code VALIDATION_FAILED} on {@code ids}), phase 1 offset, phase 2 positions 1..n.
     */
    @Transactional
    public void reorder(Long courseId, List<Long> ids) {
        Course course = lockEditableCourse(courseId);
        requirePermutation(chapters.lockIdsByCourseId(courseId), ids, "chương", "khóa học");
        chapters.shiftSortOrders(courseId);
        for (int i = 0; i < ids.size(); i++) {
            chapters.updateSortOrder(ids.get(i), i + 1);
        }
        contentChanged(course);
    }

    private Long courseIdOfChapter(Long chapterId) {
        return chapters.findCourseIdById(chapterId).orElseThrow(BusinessException::notFound);
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

    /**
     * docs/ai/03 §3.4 step 2: {@code submitted} lists every id of {@code current} exactly once. Every problem found is
     * one {@code errors[]} entry on {@code ids}; unknown ids and ids of another parent get the same text (no leak).
     */
    static void requirePermutation(List<Long> current, List<Long> submitted, String item, String parent) {
        Set<Long> currentSet = new HashSet<>(current);
        Set<Long> seen = new HashSet<>();
        Set<Long> duplicates = new LinkedHashSet<>();
        Set<Long> foreign = new LinkedHashSet<>();
        for (Long id : submitted) {
            if (!seen.add(id)) {
                duplicates.add(id);
            } else if (!currentSet.contains(id)) {
                foreign.add(id);
            }
        }
        List<Long> missing = current.stream().filter(id -> !seen.contains(id)).toList();
        List<FieldViolation> errors = new ArrayList<>();
        if (!duplicates.isEmpty()) {
            errors.add(new FieldViolation(IDS_FIELD, "Mỗi " + item + " chỉ được xuất hiện một lần, bị lặp: "
                    + duplicates));
        }
        if (!foreign.isEmpty()) {
            errors.add(new FieldViolation(IDS_FIELD, "Không thuộc " + parent + " này: " + foreign));
        }
        if (!missing.isEmpty()) {
            errors.add(new FieldViolation(IDS_FIELD, "Thiếu " + item + ": " + missing));
        }
        if (!errors.isEmpty()) {
            throw BusinessException.withErrors(ErrorCode.VALIDATION_FAILED, errors);
        }
    }
}
