package com.edumy.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.edumy.shared.text.VietnameseNormalizer;
import com.edumy.support.MySqlSeedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Coverage of the dev seed {@code db/seed/V1000__dev_seed.sql} (docs/prompts/P02-dev-seed.md, docs/ai/03 §3.7): every
 * value of every SRS §3.4 state machine is present, and the cross-table business rules the database cannot enforce
 * (BR-14, BR-27, BR-39/40, D-01) hold for the seeded rows. Read-only: only SELECTs, in the rolled-back test
 * transaction of database {@code edumy_seed}.
 */
@MySqlSeedTest
class SeedCoverageIT {

    private static final String DEV_PASSWORD = "Edumy@123";

    @Autowired
    JdbcClient jdbc;

    // ------------------------------------------------------------------------------------- SRS §3.4 state machines

    @ParameterizedTest(name = "{0}.{1}")
    @CsvSource(delimiter = ';', textBlock = """
            courses;               status;            draft|pending_review|rejected|published|hidden
            course_co_instructors; invitation_status; pending|accepted|declined|removed
            orders;                status;            pending|paid|cancelled
            orders;                cancel_reason;     user|replaced|expired
            payment_transactions;  status;            pending|success|failed|expired|needs_review
            payment_transactions;  handled_action;    activated|refund_outside
            course_reviews;        visibility_status; visible|withdrawn|hidden
            course_questions;      visibility_status; visible|deleted|hidden
            question_answers;      visibility_status; visible|deleted|hidden
            users;                 status;            active|locked
            enrollments;           enroll_source;     free|purchase
            """)
    void stateColumn_seed_containsEveryValue(String table, String column, String values) {
        // table / column come from the constant source above, never from input
        List<String> seeded = jdbc.sql("SELECT DISTINCT " + column + " FROM " + table + " WHERE " + column
                + " IS NOT NULL").query(String.class).list();
        assertThat(seeded).containsExactlyInAnyOrder(values.split("\\|"));
    }

    @Test
    void userRoles_seed_containsRevokedRole_D04() {
        assertThat(count("SELECT COUNT(*) FROM user_roles WHERE is_active = FALSE")).isPositive();
    }

    @Test
    void users_seed_containsVerifiedAndUnverifiedEmails() {
        assertThat(jdbc.sql("SELECT DISTINCT is_email_verified FROM users").query(Boolean.class).list())
                .containsExactlyInAnyOrder(true, false);
    }

    @Test
    void paymentTransactions_needsReview_bothUnhandledAndHandledRefundOutside() {
        assertThat(count("""
                SELECT COUNT(*) FROM payment_transactions WHERE status = 'needs_review' AND handled_at IS NULL"""))
                .isPositive();
        assertThat(count("""
                SELECT COUNT(*) FROM payment_transactions
                WHERE status = 'needs_review' AND handled_action = 'refund_outside'""")).isPositive();
        assertThat(count("""
                SELECT COUNT(*) FROM payment_transactions WHERE status = 'success' AND handled_action = 'activated'"""))
                .isPositive();
    }

    @Test
    void lessonProgresses_seed_containsCompletedAndNotCompleted() {
        assertThat(jdbc.sql("SELECT DISTINCT is_completed FROM lesson_progresses").query(Boolean.class).list())
                .containsExactlyInAnyOrder(true, false);
    }

    // --------------------------------------------------------------------------------------------- commerce rules

    @Test
    void orders_everyOrder_hasLinesAndTotalEqualsSumOfUnitPrices_BR27() {
        List<String> violations = jdbc.sql("""
                SELECT o.order_code FROM orders o LEFT JOIN order_items oi ON oi.order_id = o.id
                GROUP BY o.id, o.order_code, o.total_amount
                HAVING COUNT(oi.id) = 0 OR o.total_amount <> SUM(oi.unit_price)""").query(String.class).list();
        assertThat(count("SELECT COUNT(*) FROM orders")).isPositive();
        assertThat(violations).isEmpty();
    }

    @Test
    void orderItems_seed_containsPriceSnapshotDifferentFromCurrentPrice_BR12() {
        assertThat(count("""
                SELECT COUNT(*) FROM order_items oi JOIN courses c ON c.id = oi.course_id
                WHERE oi.unit_price <> c.price""")).isPositive();
    }

    @Test
    void orders_perStudent_atMostOnePending_BR29() {
        assertThat(jdbc.sql("""
                SELECT student_id FROM orders WHERE status = 'pending' GROUP BY student_id HAVING COUNT(*) > 1""")
                .query(Long.class).list()).isEmpty();
    }

    @Test
    void paymentTransactions_perOrder_atMostOnePending_BR16() {
        assertThat(jdbc.sql("""
                SELECT order_id FROM payment_transactions WHERE status = 'pending'
                GROUP BY order_id HAVING COUNT(*) > 1""").query(Long.class).list()).isEmpty();
    }

    // --------------------------------------------------------------------------------------------- learning rules

    @Test
    void purchaseEnrollments_each_pointsToPaidOrderOfSameStudentWithLineForCourse_BR14() {
        List<Long> violations = jdbc.sql("""
                SELECT e.id FROM enrollments e
                WHERE e.enroll_source = 'purchase' AND NOT EXISTS (
                    SELECT 1 FROM orders o JOIN order_items oi ON oi.order_id = o.id
                    WHERE o.id = e.order_id AND o.status = 'paid' AND o.student_id = e.student_id
                      AND oi.course_id = e.course_id)""").query(Long.class).list();
        assertThat(count("SELECT COUNT(*) FROM enrollments WHERE enroll_source = 'purchase'")).isPositive();
        assertThat(violations).isEmpty();
    }

    @Test
    void certificates_eachEnrollment_completedEveryLessonOfCourse_BR39() {
        List<String> violations = jdbc.sql("""
                SELECT cert.certificate_code FROM certificates cert
                JOIN enrollments e ON e.id = cert.enrollment_id
                JOIN chapters ch ON ch.course_id = e.course_id
                JOIN lessons l ON l.chapter_id = ch.id
                LEFT JOIN lesson_progresses lp
                  ON lp.enrollment_id = e.id AND lp.lesson_id = l.id AND lp.is_completed = TRUE
                WHERE lp.id IS NULL""").query(String.class).list();
        assertThat(count("SELECT COUNT(*) FROM certificates")).isEqualTo(2L);
        assertThat(violations).isEmpty();
    }

    @Test
    void certificates_nameSnapshots_equalStudentCourseAndMainInstructor_BR40() {
        // BINARY: the default ai_ci collation would accept a snapshot that lost its diacritics
        List<String> violations = jdbc.sql("""
                SELECT cert.certificate_code FROM certificates cert
                JOIN enrollments e ON e.id = cert.enrollment_id
                JOIN users s ON s.id = e.student_id
                JOIN courses c ON c.id = e.course_id
                JOIN users i ON i.id = c.main_instructor_id
                WHERE CAST(cert.student_name AS BINARY) <> CAST(s.full_name AS BINARY)
                   OR CAST(cert.course_title AS BINARY) <> CAST(c.title AS BINARY)
                   OR CAST(cert.instructor_name AS BINARY) <> CAST(i.full_name AS BINARY)""")
                .query(String.class).list();
        assertThat(violations).isEmpty();
    }

    // --------------------------------------------------------------------------------------- application-computed

    @Test
    void courses_titleNormalized_equalsVietnameseNormalizerOutput() {
        record Title(long id, String title, String titleNormalized) {
        }
        List<Title> titles = jdbc.sql("SELECT id, title, title_normalized FROM courses").query(Title.class).list();
        assertThat(titles).hasSize(14).allSatisfy(t -> assertThat(t.titleNormalized())
                .as("course %d", t.id()).isEqualTo(VietnameseNormalizer.normalize(t.title())));
    }

    @Test
    void users_passwordHash_matchesDevPassword() {
        List<String> hashes = jdbc.sql("SELECT DISTINCT password_hash FROM users").query(String.class).list();
        assertThat(hashes).hasSize(1);
        assertThat(new BCryptPasswordEncoder().matches(DEV_PASSWORD, hashes.getFirst())).isTrue();
    }

    @Test
    void adminRole_active_exactlyOneUser_D01() {
        List<Long> admins = jdbc.sql("""
                SELECT ur.user_id FROM user_roles ur JOIN roles r ON r.id = ur.role_id
                WHERE r.code = 'admin' AND ur.is_active = TRUE""").query(Long.class).list();
        assertThat(admins).containsExactly(1L);
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }
}
