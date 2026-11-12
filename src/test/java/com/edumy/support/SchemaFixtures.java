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
                .param("fixture" + next() + "@edumy.vn"));
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

    /** Runs an INSERT and returns the generated id. */
    public long insert(JdbcClient.StatementSpec spec) {
        KeyHolder keys = new GeneratedKeyHolder();
        spec.update(keys);
        return keys.getKey().longValue();
    }
}
