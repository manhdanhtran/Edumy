package com.edumy.shared.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import com.edumy.shared.error.ConstraintViolationTranslator.Kind;
import com.edumy.shared.error.ConstraintViolationTranslator.TranslatedViolation;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.UncategorizedSQLException;

/** No database: synthetic SQLExceptions with the exact MySQL 8.4 message formats. */
class ConstraintViolationTranslatorTest {

    // Test-only mappings, to prove "known key -> its ErrorCode" independently of the production map.
    private final ConstraintViolationTranslator translator = new ConstraintViolationTranslator(Map.of(
            "uq_carts_student", ErrorCode.CART_COURSE_ALREADY_OWNED,
            "BR-19", ErrorCode.COURSE_NOT_EDITABLE,
            "ck_courses_price", ErrorCode.VALIDATION_FAILED));

    private final ConstraintViolationTranslator production = new ConstraintViolationTranslator();

    @Test
    void unique1062_knownKey_mapsToItsErrorCode_stripsTablePrefix() {
        SQLException sql = new SQLException("Duplicate entry '7' for key 'carts.uq_carts_student'", "23000", 1062);
        var v = translator.translate(new DataIntegrityViolationException("x", sql)).orElseThrow();
        assertThat(v).isEqualTo(new TranslatedViolation(Kind.UNIQUE, 1062, "uq_carts_student",
                ErrorCode.CART_COURSE_ALREADY_OWNED));
    }

    @Test
    void unique1062_valueContainingForKeyText_usesTheLastKeyClause() {
        SQLException sql = new SQLException("Duplicate entry 'a for key ''b.c''' for key 'roles.uq_roles_code'",
                "23000", 1062);
        assertThat(translator.translate(sql).orElseThrow().key()).isEqualTo("uq_roles_code");
    }

    @Test
    void unique1062_unknownKey_dataConflictWithKeyKept() {
        SQLException sql = new SQLException("Duplicate entry 'student' for key 'roles.uq_roles_code'", "23000", 1062);
        var v = production.translate(wrap(sql, 3)).orElseThrow();
        assertThat(v.kind()).isEqualTo(Kind.UNIQUE);
        assertThat(v.key()).isEqualTo("uq_roles_code");
        assertThat(v.code()).isEqualTo(ErrorCode.DATA_CONFLICT);
    }

    @Test
    void foreignKey1451And1452_parseConstraintNameAfterCONSTRAINT() {
        String tail = "CONSTRAINT `fk_user_roles_user_id` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`))";
        SQLException delete = new SQLException(
                "Cannot delete or update a parent row: a foreign key constraint fails (`edumy`.`user_roles`, " + tail,
                "23000", 1451);
        SQLException insert = new SQLException(
                "Cannot add or update a child row: a foreign key constraint fails (`edumy`.`user_roles`, " + tail,
                "23000", 1452);
        for (SQLException sql : new SQLException[] {delete, insert}) {
            var v = translator.translate(new UncategorizedSQLException("t", "sql", sql)).orElseThrow();
            assertThat(v.kind()).isEqualTo(Kind.FOREIGN_KEY);
            assertThat(v.vendorCode()).isEqualTo(sql.getErrorCode());
            assertThat(v.key()).isEqualTo("fk_user_roles_user_id");
            // default rule: 1451 (parent still referenced) -> 409, 1452 (missing parent) -> 404
            assertThat(v.code()).isEqualTo(sql.getErrorCode() == 1452
                    ? ErrorCode.RESOURCE_NOT_FOUND : ErrorCode.DATA_CONFLICT);
        }
    }

    @Test
    void check3819_knownKey_mapsToItsErrorCode() {
        SQLException sql = new SQLException("Check constraint 'ck_courses_price' is violated.", "HY000", 3819);
        var v = translator.translate(new UncategorizedSQLException("t", "sql", sql)).orElseThrow();
        assertThat(v).isEqualTo(new TranslatedViolation(Kind.CHECK, 3819, "ck_courses_price",
                ErrorCode.VALIDATION_FAILED));
    }

    @Test
    void check3819_unknownKey_internalError() {
        SQLException sql = new SQLException("Check constraint 'ck_users_status' is violated.", "HY000", 3819);
        var v = translator.translate(wrap(sql, 2)).orElseThrow();
        assertThat(v.key()).isEqualTo("ck_users_status");
        assertThat(v.code()).isEqualTo(ErrorCode.INTERNAL_ERROR);
    }

    @Test
    void trigger1644_brCodeIsTheKey_knownMapsToItsCode() {
        SQLException sql = new SQLException("BR-19: course was published and cannot be deleted", "45000", 1644);
        var v = translator.translate(wrap(sql, 1)).orElseThrow();
        assertThat(v).isEqualTo(new TranslatedViolation(Kind.TRIGGER, 1644, "BR-19", ErrorCode.COURSE_NOT_EDITABLE));
    }

    @Test
    void trigger1644_unknownBrCode_internalError() {
        SQLException sql = new SQLException("BR-99: some future rule", "45000", 1644);
        var v = production.translate(sql).orElseThrow();
        assertThat(v.key()).isEqualTo("BR-99");
        assertThat(v.code()).isEqualTo(ErrorCode.INTERNAL_ERROR);
    }

    /** The production map of docs/ai/05 §5.2: every entry, including BOTH names of each overlapping CHECK pair. */
    @Test
    void productionMap_everyEntry() {
        Map<String, ErrorCode> expected = Map.ofEntries(
                Map.entry("BR-02", ErrorCode.COURSE_CO_INSTRUCTOR_IS_MAIN),
                Map.entry("BR-19", ErrorCode.COURSE_DELETE_FORBIDDEN),
                Map.entry("BR-24", ErrorCode.LESSON_COURSE_MISMATCH),
                Map.entry("BR-37", ErrorCode.LESSON_COURSE_MISMATCH));
        expected.forEach((br, code) -> {
            var v = production.translate(new SQLException(br + ": rule text", "45000", 1644)).orElseThrow();
            assertThat(v).as(br).isEqualTo(new TranslatedViolation(Kind.TRIGGER, 1644, br, code));
        });

        var cart = production.translate(new SQLException(
                "Duplicate entry '1-2' for key 'cart_items.uq_cart_items_cart_course'", "23000", 1062)).orElseThrow();
        assertThat(cart).isEqualTo(new TranslatedViolation(Kind.UNIQUE, 1062, "uq_cart_items_cart_course",
                ErrorCode.CART_ITEM_DUPLICATE));
    }

    /** MySQL reports only one of two overlapping CHECKs, so both names must give the same result. */
    @Test
    void productionMap_bothNamesOfOverlappingCheckPairs_mapTheSame() {
        List<List<String>> pairs = List.of(
                List.of("ck_lessons_type", "ck_lessons_content_match"),   // unknown lessons.content_type
                List.of("ck_orders_status", "ck_orders_state"),           // unknown orders.status
                List.of("ck_enrollments_source", "ck_enrollments_source_order")); // unknown enrollments.enroll_source
        for (List<String> pair : pairs) {
            var results = pair.stream().map(name -> production.translate(new SQLException(
                    "Check constraint '" + name + "' is violated.", "HY000", 3819)).orElseThrow()).toList();
            assertThat(results.get(0).code()).as(pair.toString()).isEqualTo(results.get(1).code())
                    .isEqualTo(ErrorCode.INTERNAL_ERROR);
        }
    }

    @Test
    void sqlState45000_withOtherVendorCode_isStillATrigger() {
        SQLException sql = new SQLException("BR-24: lesson not in course", "45000", 1234);
        var v = translator.translate(wrap(sql, 2)).orElseThrow();
        assertThat(v.kind()).isEqualTo(Kind.TRIGGER);
        assertThat(v.vendorCode()).isEqualTo(1234);
        assertThat(v.key()).isEqualTo("BR-24");
    }

    @Test
    void unparseableMessage_keyIsNullAndKindDefaultApplies() {
        Map<SQLException, ErrorCode> cases = new java.util.LinkedHashMap<>();
        cases.put(new SQLException("Duplicate entry", "23000", 1062), ErrorCode.DATA_CONFLICT);
        cases.put(new SQLException("fk failed", "23000", 1451), ErrorCode.DATA_CONFLICT);
        cases.put(new SQLException("fk failed", "23000", 1452), ErrorCode.RESOURCE_NOT_FOUND);
        cases.put(new SQLException("something else", "HY000", 3819), ErrorCode.INTERNAL_ERROR);
        cases.put(new SQLException("no br code here", "45000", 1644), ErrorCode.INTERNAL_ERROR);
        cases.put(new SQLException((String) null, "45000", 1644), ErrorCode.INTERNAL_ERROR);
        cases.forEach((sql, code) -> {
            var v = translator.translate(sql).orElseThrow();
            assertThat(v.key()).as(String.valueOf(sql.getMessage())).isNull();
            assertThat(v.code()).as(String.valueOf(sql.getMessage())).isEqualTo(code);
        });
    }

    @Test
    void nonConstraintVendorCode_isNotAViolation() {
        SQLException lockWait = new SQLException("Lock wait timeout exceeded", "HY000", 1205);
        assertThat(translator.translate(wrap(lockWait, 2))).isEmpty();
    }

    @Test
    void noSqlException_isNotAViolation() {
        assertThat(translator.translate(new QueryTimeoutException("timeout"))).isEmpty();
        assertThat(translator.translate(new RuntimeException("a", new IllegalStateException("b")))).isEmpty();
        assertThat(translator.translate(null)).isEmpty();
    }

    @Test
    void deepestSqlExceptionDecides() {
        SQLException outer = new SQLException("outer", "HY000", 1205);
        SQLException inner = new SQLException("Check constraint 'ck_users_status' is violated.", "HY000", 3819);
        outer.initCause(inner);
        assertThat(translator.translate(outer).orElseThrow().vendorCode()).isEqualTo(3819);
    }

    @Test
    void causeCycle_doesNotLoop() {
        RuntimeException a = new RuntimeException("a");
        RuntimeException b = new RuntimeException("b", a);
        a.initCause(b);
        assertThat(translator.translate(a)).isEmpty();

        SQLException sql = new SQLException("Check constraint 'ck_users_status' is violated.", "HY000", 3819);
        RuntimeException c = new RuntimeException("c", sql);
        sql.initCause(c);
        assertThat(translator.translate(c).orElseThrow().key()).isEqualTo("ck_users_status");
    }

    /** 1-3 wrapper layers: UncategorizedSQLException, then alternating DataIntegrityViolationException/RuntimeException. */
    private static Throwable wrap(SQLException sql, int layers) {
        Throwable t = new UncategorizedSQLException("t", "sql", sql);
        for (int i = 1; i < layers; i++) {
            t = i % 2 == 1 ? new DataIntegrityViolationException("wrapped", t) : new RuntimeException(t);
        }
        return t;
    }
}
