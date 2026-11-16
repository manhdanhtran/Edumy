package com.edumy.support;

import java.util.concurrent.atomic.AtomicLong;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

/**
 * Minimal valid parent rows for the {@code *SchemaIT} classes, inserted with plain SQL (entities do not exist yet).
 * Every value that has a UNIQUE constraint is made unique per call, so tests never collide inside the shared database;
 * the rows disappear with the rolled-back test transaction (@MySqlJdbcTest).
 */
public final class SchemaFixtures {

    private static final AtomicLong SEQ = new AtomicLong();

    private final JdbcClient jdbc;

    public SchemaFixtures(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A number unique within the test JVM, for emails, slugs, codes. */
    public static long next() {
        return SEQ.incrementAndGet();
    }

    public long user() {
        return insert(jdbc.sql("""
                INSERT INTO users (email, password_hash, full_name) VALUES (?, '$2a$12$notARealHash', 'Nguyễn Văn A')""")
                .param("fixture" + next() + "@example.com"));
    }

    public long category() {
        long n = next();
        return insert(jdbc.sql("INSERT INTO categories (name, slug) VALUES (?, ?)")
                .params("Danh mục " + n, "danh-muc-" + n));
    }

    /** A draft, free course of {@code mainInstructorId} in a new category. */
    public long course(long mainInstructorId) {
        long n = next();
        return insert(jdbc.sql("""
                INSERT INTO courses (main_instructor_id, category_id, title, title_normalized, slug)
                VALUES (?, ?, ?, ?, ?)""")
                .params(mainInstructorId, category(), "Khóa học " + n, "khoa hoc " + n, "khoa-hoc-" + n));
    }

    /** A draft, free course with a new main instructor. */
    public long course() {
        return course(user());
    }

    public long chapter(long courseId, int sortOrder) {
        return insert(jdbc.sql("INSERT INTO chapters (course_id, title, sort_order) VALUES (?, 'Chương', ?)")
                .params(courseId, sortOrder));
    }

    public long videoLesson(long chapterId, int sortOrder) {
        return insert(jdbc.sql("""
                INSERT INTO lessons (chapter_id, title, content_type, video_url, sort_order)
                VALUES (?, 'Bài video', 'video', 'https://cdn.edumy.vn/v.mp4', ?)""")
                .params(chapterId, sortOrder));
    }

    public long cart(long studentId) {
        return insert(jdbc.sql("INSERT INTO carts (student_id) VALUES (?)").param(studentId));
    }

    /**
     * An order of {@code studentId} worth 499 000 VND in {@code status} ('pending', 'paid' or 'cancelled'), with the
     * timestamps and cancel_reason that ck_orders_state requires.
     */
    public long order(long studentId, String status) {
        boolean paid = "paid".equals(status);
        boolean cancelled = "cancelled".equals(status);
        return insert(jdbc.sql("""
                INSERT INTO orders (student_id, order_code, total_amount, status, cancel_reason, paid_at, cancelled_at)
                VALUES (?, ?, 499000, ?, ?, ?, ?)""")
                .params(studentId, "ORD-" + next(), status, cancelled ? "user" : null,
                        paid ? "2026-01-01 00:00:00" : null, cancelled ? "2026-01-01 00:00:00" : null));
    }

    /** A line of {@code orderId} for {@code courseId} at 499 000 VND. */
    public long orderItem(long orderId, long courseId) {
        return insert(jdbc.sql("INSERT INTO order_items (order_id, course_id, unit_price) VALUES (?, ?, 499000)")
                .params(orderId, courseId));
    }

    /** A free enrollment (no order) of {@code studentId} in {@code courseId}. */
    public long freeEnrollment(long studentId, long courseId) {
        return insert(jdbc.sql("""
                INSERT INTO enrollments (student_id, course_id, enroll_source) VALUES (?, ?, 'free')""")
                .params(studentId, courseId));
    }

    /** A purchase enrollment of {@code studentId} in {@code courseId} through {@code orderId}. */
    public long purchaseEnrollment(long studentId, long courseId, long orderId) {
        return insert(jdbc.sql("""
                INSERT INTO enrollments (student_id, course_id, order_id, enroll_source)
                VALUES (?, ?, ?, 'purchase')""")
                .params(studentId, courseId, orderId));
    }

    /** A not-yet-completed progress row of {@code enrollmentId} on {@code lessonId}. */
    public long lessonProgress(long enrollmentId, long lessonId) {
        return insert(jdbc.sql("INSERT INTO lesson_progresses (enrollment_id, lesson_id) VALUES (?, ?)")
                .params(enrollmentId, lessonId));
    }

    /** A visible question of {@code enrollmentId}, attached to {@code lessonId} or to no lesson ({@code null}). */
    public long question(long enrollmentId, Long lessonId) {
        return insert(jdbc.sql("""
                INSERT INTO course_questions (enrollment_id, lesson_id, title, content)
                VALUES (?, ?, 'Câu hỏi', 'Nội dung câu hỏi')""")
                .params(enrollmentId, lessonId));
    }

    /** Runs an INSERT and returns the generated id. */
    public long insert(JdbcClient.StatementSpec spec) {
        KeyHolder keys = new GeneratedKeyHolder();
        spec.update(keys);
        return keys.getKey().longValue();
    }
}
