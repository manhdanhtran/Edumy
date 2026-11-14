package com.edumy.authoring.service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import com.edumy.authoring.entity.Course;
import com.edumy.authoring.repository.ChapterRepository;
import com.edumy.authoring.repository.CourseRepository;
import com.edumy.authoring.repository.LessonRepository;
import com.edumy.shared.error.BusinessException;
import com.edumy.shared.error.ErrorCode;
import com.edumy.shared.error.FieldViolation;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * UC-I17: the main instructor sends a draft or rejected course to the admin review queue. Who may call was decided by
 * {@code @courseAccess.isMain} (BR-06 main instructor only, BR-26 active instructor role in the database). The course
 * row is locked ({@code SELECT … FOR UPDATE}): content writes take the same lock, so the checklist and the transition
 * see one consistent outline.
 */
@Service
public class SubmitForReviewService {

    /**
     * Keys of the BR-07 checklist items in {@code errors[].field} of {@code COURSE_SUBMIT_CHECKLIST_FAILED}, in the order
     * they are reported (docs/ai/05 authoring P06-G4: stable contract for the SPA).
     */
    static final String TITLE = "title";
    static final String DESCRIPTION = "description";
    static final String CATEGORY = "categoryId";
    static final String PRICE = "price";
    static final String CHAPTERS = "chapters";
    static final String EMPTY_CHAPTERS = "emptyChapters";
    static final String LESSON_CONTENT = "lessonContent";

    private final CourseRepository courses;
    private final ChapterRepository chapters;
    private final LessonRepository lessons;
    private final Clock clock;

    public SubmitForReviewService(CourseRepository courses, ChapterRepository chapters, LessonRepository lessons,
                                  Clock clock) {
        this.courses = courses;
        this.chapters = chapters;
        this.lessons = lessons;
        this.clock = clock;
    }

    /**
     * Precondition first: status draft or rejected (else 409 {@code COURSE_INVALID_TRANSITION}). Then the whole BR-07
     * checklist (SRS UC-I17 step 1); E1: every failed item is reported at once in one 422
     * {@code COURSE_SUBMIT_CHECKLIST_FAILED} and nothing changes (AC-20). Pass → {@code pending_review} with
     * {@code submitted_at} = now; the course is read-only until the admin decides (BR-33). Pending or declined
     * invitations do not matter (BR-05, E3).
     */
    @Transactional
    public void submit(Long courseId) {
        Course course = courses.findByIdForUpdate(courseId).orElseThrow(BusinessException::notFound);
        if (!course.canBeSubmitted()) {
            throw new BusinessException(ErrorCode.COURSE_INVALID_TRANSITION);
        }
        List<FieldViolation> failed = checklist(course);
        if (!failed.isEmpty()) {
            throw BusinessException.withErrors(ErrorCode.COURSE_SUBMIT_CHECKLIST_FAILED, failed);
        }
        course.submitForReview(clock.instant());
        courses.flush();
    }

    /** BR-07 / D-15: one violation per failed item. */
    private List<FieldViolation> checklist(Course course) {
        List<FieldViolation> failed = new ArrayList<>();
        if (course.getTitle() == null || course.getTitle().isBlank()) {
            failed.add(new FieldViolation(TITLE, "Khóa học chưa có tên"));
        }
        if (course.getDescription() == null || course.getDescription().isBlank()) {
            failed.add(new FieldViolation(DESCRIPTION, "Khóa học chưa có mô tả"));
        }
        if (!course.getCategory().isActive()) {
            failed.add(new FieldViolation(CATEGORY, "Danh mục của khóa học đã ngừng sử dụng, hãy chọn danh mục khác"));
        }
        if (!Course.isValidPrice(course.getPrice())) {
            failed.add(new FieldViolation(PRICE,
                    "Giá phải bằng 0 hoặc từ 10.000 đ trở lên và là bội số của 1.000 đ"));
        }
        long chapterCount = chapters.countByCourseId(course.getId());
        if (chapterCount == 0) {
            failed.add(new FieldViolation(CHAPTERS, "Khóa học phải có ít nhất một chương"));
        } else {
            long empty = chapters.countWithoutLessonsByCourseId(course.getId());
            if (empty > 0) {
                failed.add(new FieldViolation(EMPTY_CHAPTERS, "Có " + empty + " chương chưa có bài học"));
            }
            long withoutContent = lessons.countWithoutContentByCourseId(course.getId());
            if (withoutContent > 0) {
                failed.add(new FieldViolation(LESSON_CONTENT, "Có " + withoutContent
                        + " bài học chưa có nội dung (bài video cần đường dẫn video, bài viết cần nội dung)"));
            }
        }
        return failed;
    }
}
