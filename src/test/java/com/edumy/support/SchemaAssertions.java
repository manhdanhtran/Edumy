package com.edumy.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.List;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Assertions shared by the {@code *SchemaIT} classes (same checks as {@code IdentitySchemaIT}): every violation asserts
 * the constraint name or BR code, because the error translator maps them to API error codes (docs/ai/03, 05 §5.2).
 */
public final class SchemaAssertions {

    private SchemaAssertions() {
    }

    /**
     * CHECK violation: MySQL error 3819. Spring JDBC 7.0 does not map 3819 to DataIntegrityViolationException, so only
     * DataAccessException is asserted; the root SQLException carries the code and the constraint name.
     */
    public static void assertCheckViolated(String constraint, ThrowingCallable statement) {
        assertThatThrownBy(statement)
                .isInstanceOf(DataAccessException.class)
                .satisfies(e -> {
                    Throwable cause = NestedExceptionUtils.getMostSpecificCause(e);
                    assertThat(cause).isInstanceOf(SQLException.class);
                    assertThat(((SQLException) cause).getErrorCode()).isEqualTo(3819);
                    assertThat(cause.getMessage()).isEqualTo("Check constraint '" + constraint + "' is violated.");
                });
    }

    /**
     * CHECK violation where the row breaks several CHECKs at once and MySQL reports only the first one it evaluates
     * (e.g. an unknown lessons.content_type breaks ck_lessons_type and ck_lessons_content_match).
     */
    public static void assertCheckViolatedAnyOf(ThrowingCallable statement, String... constraints) {
        assertThatThrownBy(statement)
                .isInstanceOf(DataAccessException.class)
                .satisfies(e -> {
                    Throwable cause = NestedExceptionUtils.getMostSpecificCause(e);
                    assertThat(cause).isInstanceOf(SQLException.class);
                    assertThat(((SQLException) cause).getErrorCode()).isEqualTo(3819);
                    assertThat(cause.getMessage()).isIn(java.util.Arrays.stream(constraints)
                            .map(c -> "Check constraint '" + c + "' is violated.").toList());
                });
    }

    /** {@code ENFORCED} flag and clause of a CHECK constraint, or an empty list if it does not exist. */
    public static List<String> checkConstraint(JdbcClient jdbc, String table, String constraint) {
        return jdbc.sql("""
                SELECT CONCAT(tc.ENFORCED, ' ', cc.CHECK_CLAUSE)
                FROM information_schema.TABLE_CONSTRAINTS tc
                JOIN information_schema.CHECK_CONSTRAINTS cc
                  ON cc.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA AND cc.CONSTRAINT_NAME = tc.CONSTRAINT_NAME
                WHERE tc.TABLE_SCHEMA = DATABASE() AND tc.TABLE_NAME = ? AND tc.CONSTRAINT_NAME = ?
                  AND tc.CONSTRAINT_TYPE = 'CHECK'""")
                .params(table, constraint).query(String.class).list();
    }

    /** UNIQUE (1062) and FK (1451/1452) violations, translated by Spring to DataIntegrityViolationException. */
    public static void assertViolates(String constraint, ThrowingCallable statement) {
        assertThatThrownBy(statement)
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(NestedExceptionUtils.getMostSpecificCause(e).getMessage())
                        .contains(constraint));
    }

    /** Trigger SIGNAL SQLSTATE '45000': MySQL error 1644, message starting with the BR code (docs/ai/03 §3.5). */
    public static void assertSignals(String brCode, ThrowingCallable statement) {
        assertThatThrownBy(statement)
                .isInstanceOf(DataAccessException.class)
                .satisfies(e -> {
                    Throwable cause = NestedExceptionUtils.getMostSpecificCause(e);
                    assertThat(cause).isInstanceOf(SQLException.class);
                    SQLException sql = (SQLException) cause;
                    assertThat(sql.getErrorCode()).isEqualTo(1644);
                    assertThat(sql.getSQLState()).isEqualTo("45000");
                    assertThat(sql.getMessage()).startsWith(brCode + ":");
                });
    }

    /** {@code CONSTRAINT_NAME=DELETE_RULE} of every FK declared on the given tables, sorted by constraint name. */
    public static List<String> foreignKeyDeleteRules(JdbcClient jdbc, String... tables) {
        return jdbc.sql("""
                SELECT CONSTRAINT_NAME, DELETE_RULE FROM information_schema.REFERENTIAL_CONSTRAINTS
                WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME IN (:tables)
                ORDER BY CONSTRAINT_NAME""")
                .param("tables", List.of(tables))
                .query((rs, n) -> rs.getString(1) + "=" + rs.getString(2)).list();
    }

    /** Columns of an index in key order; a descending key part is returned as {@code "<column> DESC"}. */
    public static List<String> indexColumns(JdbcClient jdbc, String table, String index) {
        return jdbc.sql("""
                SELECT CASE WHEN COLLATION = 'D' THEN CONCAT(COLUMN_NAME, ' DESC') ELSE COLUMN_NAME END
                FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ? ORDER BY SEQ_IN_INDEX""")
                .params(table, index).query(String.class).list();
    }
}
