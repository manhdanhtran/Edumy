package com.edumy.schema;

import static com.edumy.support.SchemaAssertions.indexColumns;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.edumy.support.MySqlJdbcTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The whole db/migration chain V1–V6 on real MySQL 8.4 (docs/ai/03 §3.7): the 21 tables (20 of EDU-DDD-001 +
 * refresh_tokens, ADR-002), the generated columns that replace the partial UNIQUE indexes (§3.3), the 7 triggers
 * (§3.5), the reference roles (V6), the Flyway history and the FK totals of DDD §6 / §8 row 30.
 */
@MySqlJdbcTest
class FlywayMigrationIT {

    @Autowired
    JdbcClient jdbc;

    @Test
    void tables_are20DddTablesPlusRefreshTokens() {
        List<String> tables = jdbc.sql("""
                SELECT TABLE_NAME FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE'
                  AND TABLE_NAME <> 'flyway_schema_history'""")
                .query(String.class).list();

        assertThat(tables).containsExactlyInAnyOrder(
                // identity (§5.1–5.4) + ADR-002
                "users", "roles", "user_roles", "user_tokens", "refresh_tokens",
                // authoring (§5.5–5.9)
                "categories", "courses", "course_co_instructors", "chapters", "lessons",
                // commerce (§5.10–5.14)
                "carts", "cart_items", "orders", "order_items", "payment_transactions",
                // learning (§5.15–5.20)
                "enrollments", "lesson_progresses", "course_reviews", "course_questions", "question_answers",
                "certificates");
    }

    @Test
    void generatedColumns_areStoredAndUnique() {
        List<String> generated = jdbc.sql("""
                SELECT CONCAT(TABLE_NAME, '.', COLUMN_NAME, ' ', DATA_TYPE, ' ', EXTRA) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND GENERATION_EXPRESSION <> ''""")
                .query(String.class).list();

        assertThat(generated).containsExactlyInAnyOrder(
                "orders.pending_student_id bigint STORED GENERATED",
                "payment_transactions.pending_order_id bigint STORED GENERATED");
        assertThat(uniqueConstraint("orders", "uq_orders_one_pending")).isTrue();
        assertThat(indexColumns(jdbc, "orders", "uq_orders_one_pending")).containsExactly("pending_student_id");
        assertThat(uniqueConstraint("payment_transactions", "uq_payment_one_pending")).isTrue();
        assertThat(indexColumns(jdbc, "payment_transactions", "uq_payment_one_pending"))
                .containsExactly("pending_order_id");
    }

    @Test
    void triggers_exactlySeven() {
        List<String> triggers = jdbc.sql("""
                SELECT TRIGGER_NAME FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA = DATABASE()""")
                .query(String.class).list();

        assertThat(triggers).containsExactlyInAnyOrder(
                "trg_cci_not_main_instructor_bi", "trg_cci_not_main_instructor_bu",
                "trg_courses_prevent_delete_published_bd",
                "trg_lesson_progresses_lesson_course_bi", "trg_lesson_progresses_lesson_course_bu",
                "trg_course_questions_lesson_course_bi", "trg_course_questions_lesson_course_bu");
    }

    @Test
    void roles_areTheThreeReferenceRolesWithVietnameseNames() {
        List<String> roles = jdbc.sql("SELECT CONCAT(code, '=', name) FROM roles ORDER BY code")
                .query(String.class).list();

        assertThat(roles).containsExactly("admin=Quản trị viên", "instructor=Giảng viên", "student=Học viên");
        // stored as real utf8mb4 characters, not double-encoded bytes: 'Học viên' = 8 characters, 11 bytes
        // (6 ASCII + 'ọ' U+1ECD 3 bytes + 'ê' U+00EA 2 bytes)
        var lengths = jdbc.sql("""
                SELECT CHAR_LENGTH(name) AS chars, LENGTH(name) AS bytes FROM roles WHERE code = 'student'""")
                .query().singleRow();
        assertThat(((Number) lengths.get("chars")).longValue()).isEqualTo(8L);
        assertThat(((Number) lengths.get("bytes")).longValue()).isEqualTo(11L);
    }

    @Test
    void flywayHistory_versions1To6AllSuccessful() {
        List<String> versions = jdbc.sql("""
                SELECT version FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank""")
                .query(String.class).list();
        assertThat(versions).containsExactly("1", "2", "3", "4", "5", "6");

        assertThat(jdbc.sql("SELECT COUNT(*) FROM flyway_schema_history WHERE success <> 1").query(Long.class).single())
                .isZero();
        assertThat(jdbc.sql("""
                SELECT version FROM flyway_schema_history WHERE success = 1 AND version IS NOT NULL
                ORDER BY CAST(version AS UNSIGNED) DESC LIMIT 1""").query(String.class).single()).isEqualTo("6");
    }

    /**
     * DDD §6 lists 34 FKs on its 20 tables; §8 row 30 says 27 of them are RESTRICT; the rest are 6 CASCADE (#4, 8, 11,
     * 12, 14, 25) and 1 SET NULL (#29). refresh_tokens (ADR-002, not in the DDD) adds fk_refresh_tokens_user_id
     * CASCADE, so the schema holds 35 FKs.
     */
    @Test
    void foreignKeys_34DddFksByDeleteRule_plusAdr002() {
        Map<String, Long> dddRules = jdbc.sql("""
                SELECT DELETE_RULE, COUNT(*) FROM information_schema.REFERENTIAL_CONSTRAINTS
                WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME <> 'refresh_tokens' GROUP BY DELETE_RULE""")
                .query((rs, n) -> Map.entry(rs.getString(1), rs.getLong(2))).list().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        assertThat(dddRules).containsExactlyInAnyOrderEntriesOf(Map.of("RESTRICT", 27L, "CASCADE", 6L, "SET NULL", 1L));
        assertThat(dddRules.values().stream().mapToLong(Long::longValue).sum()).isEqualTo(34L);

        List<String> adrFks = jdbc.sql("""
                SELECT CONCAT(CONSTRAINT_NAME, '=', DELETE_RULE) FROM information_schema.REFERENTIAL_CONSTRAINTS
                WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'refresh_tokens'""")
                .query(String.class).list();
        assertThat(adrFks).containsExactly("fk_refresh_tokens_user_id=CASCADE");
    }

    private boolean uniqueConstraint(String table, String constraint) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND CONSTRAINT_NAME = ?
                  AND CONSTRAINT_TYPE = 'UNIQUE'""")
                .params(table, constraint).query(Long.class).single() == 1L;
    }
}
