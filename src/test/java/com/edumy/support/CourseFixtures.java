package com.edumy.support;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

/**
 * Committed authoring / learning rows for {@link MySqlSpringBootTest} tests that go through HTTP (the request runs in
 * its own transaction, so the data must be committed). Plain SQL, no entity. A course marked published / hidden
 * ({@code published_at} set) is reset to draft by {@link #deleteAll()} first, so the BR-19 trigger lets it go. Rows
 * created through the API are removed too once {@link #trackCourse} / {@link #trackCategory} was called. Call
 * {@link #deleteAll()} in {@code @AfterEach} <b>before</b> {@code TestUsers.deleteAll()} (these rows reference the
 * users).
 */
public class CourseFixtures {

    private final JdbcClient jdbc;
    private final List<Long> categories = new ArrayList<>();
    private final List<Long> courses = new ArrayList<>();
    private final List<Long> enrollments = new ArrayList<>();
    private final List<Long> questions = new ArrayList<>();

    public CourseFixtures(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A draft free course of {@code mainInstructorId} in a new category. */
    public long draftCourse(long mainInstructorId) {
        String suffix = UUID.randomUUID().toString();
        long category = insert(jdbc.sql("INSERT INTO categories (name, slug) VALUES (?, ?)")
                .params("Danh mục " + suffix, "danh-muc-" + suffix));
        categories.add(category);
        long course = insert(jdbc.sql("""
                        INSERT INTO courses (main_instructor_id, category_id, title, title_normalized, slug)
                        VALUES (?, ?, ?, ?, ?)""")
                .params(mainInstructorId, category, "Khóa học " + suffix, "khoa hoc " + suffix, "khoa-hoc-" + suffix));
        courses.add(course);
        return course;
    }

    /** A new category ({@code active} or not), deleted by {@link #deleteAll()}. */
    public long category(boolean active) {
        String suffix = UUID.randomUUID().toString();
        long category = insert(jdbc.sql("INSERT INTO categories (name, slug, is_active) VALUES (?, ?, ?)")
                .params("Danh mục " + suffix, "danh-muc-" + suffix, active));
        categories.add(category);
        return category;
    }

    /** A draft free course of {@code mainInstructorId} in an existing category. */
    public long draftCourse(long mainInstructorId, long categoryId) {
        String suffix = UUID.randomUUID().toString();
        long course = insert(jdbc.sql("""
                        INSERT INTO courses (main_instructor_id, category_id, title, title_normalized, slug)
                        VALUES (?, ?, ?, ?, ?)""")
                .params(mainInstructorId, categoryId, "Khóa học " + suffix, "khoa hoc " + suffix,
                        "khoa-hoc-" + suffix));
        courses.add(course);
        return course;
    }

    /** A course created through the API: {@link #deleteAll()} removes it (no-op if the test deleted it). */
    public void trackCourse(long courseId) {
        courses.add(courseId);
    }

    /** A category created through the API: {@link #deleteAll()} removes it (no-op if the test deleted it). */
    public void trackCategory(long categoryId) {
        categories.add(categoryId);
    }

    /**
     * Sets {@code status} with the columns its CHECKs need, bypassing the state machine: {@code published} (and
     * {@code published_at}), {@code hidden} / {@code rejected} (+ {@code review_note}); {@code publishedOnce} also sets
     * {@code published_at} on a rejected course (a state the machine never produces, for BR-19 checks).
     */
    public void setStatus(long courseId, String status, boolean publishedOnce) {
        jdbc.sql("""
                        UPDATE courses SET status = ?, review_note = CASE WHEN ? IN ('rejected', 'hidden') THEN 'Lý do' END,
                               published_at = CASE WHEN ? THEN CURRENT_TIMESTAMP(6) END,
                               submitted_at = CASE WHEN ? = 'pending_review' THEN CURRENT_TIMESTAMP(6) END
                        WHERE id = ?""")
                .params(status, status, publishedOnce || "published".equals(status) || "hidden".equals(status),
                        status, courseId)
                .update();
    }

    /** draft → pending_review (with the submitted_at that ck_courses_submitted_at requires). */
    public void markPendingReview(long courseId) {
        jdbc.sql("UPDATE courses SET status = 'pending_review', submitted_at = CURRENT_TIMESTAMP(6) WHERE id = ?")
                .param(courseId)
                .update();
    }

    /** A co-instructor row in {@code status} (pending / accepted / declined / removed), invited by {@code invitedBy}. */
    public void coInstructor(long courseId, long instructorId, long invitedBy, String status) {
        boolean responded = "accepted".equals(status) || "declined".equals(status) || "removed".equals(status);
        jdbc.sql("""
                        INSERT INTO course_co_instructors (course_id, instructor_id, invited_by, invitation_status,
                                                           responded_at)
                        VALUES (?, ?, ?, ?, CASE WHEN ? THEN CURRENT_TIMESTAMP(6) END)""")
                .params(courseId, instructorId, invitedBy, status, responded)
                .update();
    }

    public long chapter(long courseId) {
        return insert(jdbc.sql("INSERT INTO chapters (course_id, title, sort_order) VALUES (?, 'Chương 1', 1)")
                .param(courseId));
    }

    public long lesson(long chapterId) {
        return insert(jdbc.sql("""
                        INSERT INTO lessons (chapter_id, title, content_type, video_url, sort_order)
                        VALUES (?, 'Bài 1', 'video', 'https://cdn.edumy.vn/v.mp4', 1)""")
                .param(chapterId));
    }

    /** A free enrollment of {@code studentId} in {@code courseId}. */
    public long enrollment(long studentId, long courseId) {
        long id = insert(jdbc.sql("INSERT INTO enrollments (student_id, course_id, enroll_source) VALUES (?, ?, 'free')")
                .params(studentId, courseId));
        enrollments.add(id);
        return id;
    }

    /** A visible course-level question of the enrollment's student. */
    public long question(long enrollmentId) {
        long id = insert(jdbc.sql("""
                        INSERT INTO course_questions (enrollment_id, title, content) VALUES (?, 'Câu hỏi', 'Nội dung')""")
                .param(enrollmentId));
        questions.add(id);
        return id;
    }

    /** Sets the question deleted (asker) or hidden (by {@code adminId}, BR-35 needs moderated_by / moderated_at). */
    public void questionVisibility(long questionId, String visibility, Long adminId) {
        jdbc.sql("""
                        UPDATE course_questions SET visibility_status = ?, moderated_by = ?,
                               moderated_at = CASE WHEN ? THEN CURRENT_TIMESTAMP(6) END
                        WHERE id = ?""")
                .params(visibility, adminId, adminId != null, questionId)
                .update();
    }

    /** A visible answer of {@code authorId} ({@code authorType} instructor / student). */
    public long answer(long questionId, long authorId, String authorType) {
        return insert(jdbc.sql("""
                        INSERT INTO question_answers (question_id, author_id, author_type, content)
                        VALUES (?, ?, ?, 'Trả lời')""")
                .params(questionId, authorId, authorType));
    }

    /** Deletes everything created since the last call, children first (chapters, lessons, co-instructors cascade). */
    public void deleteAll() {
        for (Long id : questions) {
            jdbc.sql("DELETE FROM question_answers WHERE question_id = ?").param(id).update();
            jdbc.sql("DELETE FROM course_questions WHERE id = ?").param(id).update();
        }
        for (Long id : enrollments) {
            jdbc.sql("DELETE FROM enrollments WHERE id = ?").param(id).update();
        }
        for (Long id : courses) {
            // BR-19 trigger: a course with published_at cannot be deleted, so reset it to a never-published draft
            jdbc.sql("UPDATE courses SET status = 'draft', published_at = NULL WHERE id = ?").param(id).update();
            jdbc.sql("DELETE FROM courses WHERE id = ?").param(id).update();
        }
        for (Long id : categories) {
            jdbc.sql("DELETE FROM categories WHERE id = ?").param(id).update();
        }
        questions.clear();
        enrollments.clear();
        courses.clear();
        categories.clear();
    }

    private static long insert(JdbcClient.StatementSpec statement) {
        KeyHolder keys = new GeneratedKeyHolder();
        statement.update(keys);
        return keys.getKey().longValue();
    }
}
