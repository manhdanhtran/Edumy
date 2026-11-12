package com.edumy.shared.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import com.edumy.shared.error.ConstraintViolationTranslator.Kind;
import com.edumy.shared.error.ConstraintViolationTranslator.TranslatedViolation;
import com.edumy.support.MySqlSpringBootTest;
import com.edumy.support.SchemaFixtures;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every constraint family on real MySQL 8.4, provoked through BOTH persistence paths: {@code JdbcClient} and Hibernate
 * ({@code EntityManager.createNativeQuery(...).executeUpdate()}, entities do not exist yet so native SQL only).
 *
 * <p>Evidence that deciding by vendor code is necessary: the same violation surfaces as a different exception family
 * per path (observed on Spring 7 / Hibernate 7.4). {@code JdbcClient} throws Spring {@link DataAccessException}s:
 * DuplicateKeyException for 1062, DataIntegrityViolationException for 1451/1452, and a plain UncategorizedSQLException
 * (not a DataIntegrityViolationException) for 3819 and 1644. A raw {@code EntityManager} call throws Hibernate
 * exceptions, a {@link PersistenceException} subtree: ConstraintViolationException for 1062/1451/1452/3819 and
 * GenericJDBCException for 1644; none of them is a Spring exception. Both paths end in the same MySQL SQLException.
 *
 * <p>Every row of the mapping table in docs/ai/05 §5.2 (mapped keys, overlapping CHECK pairs, per-kind defaults) is
 * provoked through both paths. A failed Hibernate statement marks the surrounding transaction rollback-only, so each test method provokes one
 * violation. Tests are {@code @Transactional}: parent rows roll back.
 */
@MySqlSpringBootTest
@Transactional
class ConstraintViolationTranslatorIT {

    enum Path { JDBC_CLIENT, HIBERNATE_NATIVE }

    /** The production bean (docs/ai/05 §5.2): the real key -> ErrorCode map plus the per-kind defaults. */
    @Autowired
    ConstraintViolationTranslator production;

    @Autowired
    JdbcClient jdbc;

    @PersistenceContext
    EntityManager em;

    SchemaFixtures fx;

    @BeforeEach
    void setUp() {
        fx = new SchemaFixtures(jdbc);
    }

    // ------------------------------------------------------------------------------------ mapped keys (production map)

    @ParameterizedTest
    @EnumSource(Path.class)
    void insertMainInstructorAsCoInstructor_1644_BR02_coInstructorIsMain(Path path) {
        long owner = fx.user();
        long course = fx.course(owner);
        long inviter = fx.user();
        Throwable ex = provoke(path, "INSERT INTO course_co_instructors (course_id, instructor_id, invited_by) "
                + "VALUES (" + course + ", " + owner + ", " + inviter + ")");
        assertTranslated(ex, Kind.TRIGGER, 1644, ErrorCode.COURSE_CO_INSTRUCTOR_IS_MAIN, "BR-02");
    }

    @ParameterizedTest
    @EnumSource(Path.class)
    void deletePublishedCourse_1644_BR19_deleteForbidden(Path path) {
        long course = fx.course();
        jdbc.sql("UPDATE courses SET status = 'published', published_at = '2026-01-01 00:00:00' WHERE id = ?")
                .param(course).update();
        Throwable ex = provoke(path, "DELETE FROM courses WHERE id = " + course);
        assertTranslated(ex, Kind.TRIGGER, 1644, ErrorCode.COURSE_DELETE_FORBIDDEN, "BR-19");
    }

    @ParameterizedTest
    @EnumSource(Path.class)
    void progressOnLessonOfAnotherCourse_1644_BR24_lessonCourseMismatch(Path path) {
        long enrollment = fx.freeEnrollment(fx.user(), fx.course());
        long foreignLesson = fx.videoLesson(fx.chapter(fx.course(), 1), 1);
        Throwable ex = provoke(path, "INSERT INTO lesson_progresses (enrollment_id, lesson_id) VALUES ("
                + enrollment + ", " + foreignLesson + ")");
        assertTranslated(ex, Kind.TRIGGER, 1644, ErrorCode.LESSON_COURSE_MISMATCH, "BR-24");
    }

    @ParameterizedTest
    @EnumSource(Path.class)
    void questionOnLessonOfAnotherCourse_1644_BR37_lessonCourseMismatch(Path path) {
        long enrollment = fx.freeEnrollment(fx.user(), fx.course());
        long foreignLesson = fx.videoLesson(fx.chapter(fx.course(), 1), 1);
        Throwable ex = provoke(path, "INSERT INTO course_questions (enrollment_id, lesson_id, title, content) VALUES ("
                + enrollment + ", " + foreignLesson + ", 'Tiêu đề', 'Nội dung')");
        assertTranslated(ex, Kind.TRIGGER, 1644, ErrorCode.LESSON_COURSE_MISMATCH, "BR-37");
    }

    @ParameterizedTest
    @EnumSource(Path.class)
    void courseTwiceInCart_1062_cartItemDuplicate(Path path) {
        long cart = fx.cart(fx.user());
        long course = fx.course();
        jdbc.sql("INSERT INTO cart_items (cart_id, course_id) VALUES (?, ?)").params(cart, course).update();
        Throwable ex = provoke(path, "INSERT INTO cart_items (cart_id, course_id) VALUES (" + cart + ", " + course + ")");
        assertTranslated(ex, Kind.UNIQUE, 1062, ErrorCode.CART_ITEM_DUPLICATE, "uq_cart_items_cart_course");
    }

    // ---------------------------------------------- overlapping CHECK pairs: MySQL reports one of two names, both map

    @ParameterizedTest
    @EnumSource(Path.class)
    void lessonWithUnknownContentType_3819_eitherNameIsInternalError(Path path) {
        long chapter = fx.chapter(fx.course(), 1);
        Throwable ex = provoke(path, "INSERT INTO lessons (chapter_id, title, content_type, sort_order) VALUES ("
                + chapter + ", 'Bài', 'quiz', 1)");
        assertTranslated(ex, Kind.CHECK, 3819, ErrorCode.INTERNAL_ERROR, "ck_lessons_type", "ck_lessons_content_match");
    }

    @ParameterizedTest
    @EnumSource(Path.class)
    void orderWithUnknownStatus_3819_eitherNameIsInternalError(Path path) {
        long student = fx.user();
        Throwable ex = provoke(path, "INSERT INTO orders (student_id, order_code, total_amount, status) VALUES ("
                + student + ", 'ORD-T" + SchemaFixtures.next() + "', 1000, 'refunded')");
        assertTranslated(ex, Kind.CHECK, 3819, ErrorCode.INTERNAL_ERROR, "ck_orders_status", "ck_orders_state");
    }

    @ParameterizedTest
    @EnumSource(Path.class)
    void enrollmentWithUnknownSource_3819_eitherNameIsInternalError(Path path) {
        long student = fx.user();
        long course = fx.course();
        Throwable ex = provoke(path, "INSERT INTO enrollments (student_id, course_id, enroll_source) VALUES ("
                + student + ", " + course + ", 'gift')");
        assertTranslated(ex, Kind.CHECK, 3819, ErrorCode.INTERNAL_ERROR,
                "ck_enrollments_source", "ck_enrollments_source_order");
    }

    // ------------------------------------------------------------------------- per-kind defaults (key not mapped)

    @ParameterizedTest
    @EnumSource(Path.class)
    void unmappedUnique_1062_dataConflict(Path path) {
        // V6 seeded roles.code 'student'
        Throwable ex = provoke(path, "INSERT INTO roles (code, name) VALUES ('student', 'Học viên')");
        assertTranslated(ex, Kind.UNIQUE, 1062, ErrorCode.DATA_CONFLICT, "uq_roles_code");
    }

    @ParameterizedTest
    @EnumSource(Path.class)
    void fkMissingParent_1452_resourceNotFound(Path path) {
        Throwable ex = provoke(path, "INSERT INTO user_roles (user_id, role_id) VALUES (-1, " + studentRoleId() + ")");
        assertTranslated(ex, Kind.FOREIGN_KEY, 1452, ErrorCode.RESOURCE_NOT_FOUND, "fk_user_roles_user_id");
    }

    @ParameterizedTest
    @EnumSource(Path.class)
    void fkParentStillReferenced_1451_dataConflict(Path path) {
        long user = fx.user();
        jdbc.sql("INSERT INTO user_roles (user_id, role_id) VALUES (?, ?)").params(user, studentRoleId()).update();
        Throwable ex = provoke(path, "DELETE FROM users WHERE id = " + user);
        assertTranslated(ex, Kind.FOREIGN_KEY, 1451, ErrorCode.DATA_CONFLICT, "fk_user_roles_user_id");
    }

    @ParameterizedTest
    @EnumSource(Path.class)
    void unmappedCheck_3819_internalError(Path path) {
        // CHECKs compare CAST(status AS BINARY): 'Active' is not 'active'
        Throwable ex = provoke(path, "INSERT INTO users (email, password_hash, full_name, status) VALUES "
                + "('translator" + SchemaFixtures.next() + "@edumy.vn', 'x', 'Nguyễn Văn A', 'Active')");
        assertTranslated(ex, Kind.CHECK, 3819, ErrorCode.INTERNAL_ERROR, "ck_users_status");
    }

    // No real unmapped trigger exists (all four BR codes of V5 are mapped); "unmapped trigger -> INTERNAL_ERROR" is
    // covered with a synthetic BR-99 in ConstraintViolationTranslatorTest and GlobalExceptionHandlerTest.

    // ------------------------------------------------------------------------------------------------------ helpers

    private long studentRoleId() {
        return jdbc.sql("SELECT id FROM roles WHERE code = 'student'").query(Long.class).single();
    }

    /** Runs the statement through the chosen path, asserts the family of the thrown exception and returns it. */
    private Throwable provoke(Path path, String sql) {
        try {
            switch (path) {
                case JDBC_CLIENT -> jdbc.sql(sql).update();
                case HIBERNATE_NATIVE -> em.createNativeQuery(sql).executeUpdate();
            }
        } catch (RuntimeException ex) {
            assertThat(ex).isInstanceOf(path == Path.JDBC_CLIENT ? DataAccessException.class : PersistenceException.class);
            return ex;
        }
        throw new AssertionError("statement did not fail: " + sql);
    }

    /**
     * The production translator yields kind, vendor code, one of the expected keys and the expected ErrorCode; and the
     * parsed key is usable as a map key (a translator mapping exactly that key to another code overrides the default).
     */
    private void assertTranslated(Throwable ex, Kind kind, int vendor, ErrorCode expected, String... keys) {
        TranslatedViolation v = production.translate(ex).orElseThrow();
        assertThat(v.kind()).isEqualTo(kind);
        assertThat(v.vendorCode()).isEqualTo(vendor);
        assertThat(v.key()).isIn((Object[]) keys);
        assertThat(v.code()).isEqualTo(expected);

        TranslatedViolation overridden = new ConstraintViolationTranslator(Map.of(v.key(), ErrorCode.CART_OWN_COURSE))
                .translate(ex).orElseThrow();
        assertThat(overridden.code()).isEqualTo(ErrorCode.CART_OWN_COURSE);
    }
}
