package com.edumy.commerce;

import static com.edumy.support.SchemaAssertions.assertCheckViolated;
import static com.edumy.support.SchemaAssertions.assertCheckViolatedAnyOf;
import static com.edumy.support.SchemaAssertions.assertViolates;
import static com.edumy.support.SchemaAssertions.checkConstraint;
import static com.edumy.support.SchemaAssertions.foreignKeyDeleteRules;
import static com.edumy.support.SchemaAssertions.indexColumns;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

import com.edumy.support.MySqlJdbcTest;
import com.edumy.support.SchemaFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * V3__commerce.sql on real MySQL 8.4: every CHECK / UNIQUE / FK of carts, cart_items, orders, order_items,
 * payment_transactions (EDU-DDD-001 §5.10–5.14, §6 #13–20, §8 rows 14–21, §9), including the STORED generated columns
 * that replace the partial UNIQUE indexes uq_orders_one_pending / uq_payment_one_pending (docs/ai/03 §3.3).
 * Each violation asserts the constraint name. Each test runs in a rolled-back transaction (@JdbcTest).
 */
@MySqlJdbcTest
class CommerceSchemaIT {

    private static final String PAST = "2026-01-01 00:00:00";
    private static final String FUTURE = "2099-01-01 00:00:00";

    @Autowired
    JdbcClient jdbc;

    SchemaFixtures fx;

    @BeforeEach
    void setUp() {
        fx = new SchemaFixtures(jdbc);
    }

    // ---------------------------------------------------------------------------------------------------------- carts

    @Test
    void insertCart_valid_storesTimestamps() {
        long id = fx.cart(fx.user());

        var row = jdbc.sql("SELECT created_at, updated_at FROM carts WHERE id = ?").param(id).query().singleRow();
        assertThat(row.get("created_at")).isNotNull();
        assertThat(row.get("updated_at")).isNotNull();
    }

    @Test
    void insertCart_secondCartForStudent_rejected_BR22() {
        long student = fx.user();
        fx.cart(student);
        assertViolates("uq_carts_student", () -> fx.cart(student));
    }

    @Test
    void insertCart_unknownStudent_rejected() {
        assertViolates("fk_carts_student_id", () -> fx.cart(-1L));
    }

    @Test
    void updateCart_anyColumn_refreshesUpdatedAt() {
        // carts has no business column besides student_id; the application touches the row when its items change
        long id = fx.cart(fx.user());
        assertUpdatedAtRefreshed("carts", id, "created_at = '2026-02-01 00:00:00'");
    }

    // ----------------------------------------------------------------------------------------------------- cart_items

    @Test
    void insertCartItem_valid_storesAddedAt() {
        long id = insertCartItem(fx.cart(fx.user()), fx.course());

        assertThat(jdbc.sql("SELECT added_at FROM cart_items WHERE id = ?").param(id).query(LocalDateTime.class)
                .single()).isNotNull();
    }

    @Test
    void insertCartItem_sameCourseTwiceInCart_rejected_BR11() {
        long cart = fx.cart(fx.user());
        long course = fx.course();
        insertCartItem(cart, course);
        assertViolates("uq_cart_items_cart_course", () -> insertCartItem(cart, course));
    }

    @Test
    void insertCartItem_sameCourseInAnotherCart_accepted() {
        long course = fx.course();
        insertCartItem(fx.cart(fx.user()), course);
        long id = insertCartItem(fx.cart(fx.user()), course);
        assertThat(id).isPositive();
    }

    @Test
    void insertCartItem_unknownCart_rejected() {
        long course = fx.course();
        assertViolates("fk_cart_items_cart_id", () -> insertCartItem(-1L, course));
    }

    @Test
    void insertCartItem_unknownCourse_rejected() {
        long cart = fx.cart(fx.user());
        assertViolates("fk_cart_items_course_id", () -> insertCartItem(cart, -1L));
    }

    // --------------------------------------------------------------------------------------------------------- orders

    @Test
    void insertOrder_valid_appliesDefaults() {
        long student = fx.user();
        long id = insertOrder(student, "ORD-" + SchemaFixtures.next(), 499_000L);

        var row = jdbc.sql("""
                SELECT status, cancel_reason, paid_at, cancelled_at, created_at, updated_at, pending_student_id
                FROM orders WHERE id = ?""").param(id).query().singleRow();
        assertThat(row.get("status")).isEqualTo("pending");
        assertThat(row).containsEntry("cancel_reason", null).containsEntry("paid_at", null)
                .containsEntry("cancelled_at", null);
        assertThat(row.get("created_at")).isNotNull();
        assertThat(row.get("updated_at")).isNotNull();
        assertThat(row.get("pending_student_id")).isEqualTo(student);
    }

    @Test
    void insertOrder_everyValidState_accepted_D25() {
        long student = fx.user();
        insertOrder(student, "pending", null, null, null);
        insertOrder(student, "paid", null, PAST, null);
        insertOrder(student, "cancelled", "user", null, PAST);
        insertOrder(student, "cancelled", "replaced", null, PAST);
        insertOrder(student, "cancelled", "expired", null, PAST);
        // paid after cancel: cancelled_at and cancel_reason are kept (DDD §7)
        long late = insertOrder(student, "paid", "expired", PAST, PAST);

        assertThat(jdbc.sql("SELECT COUNT(*) FROM orders WHERE student_id = ?").param(student)
                .query(Long.class).single()).isEqualTo(6L);
        assertThat(pendingKey("orders", "pending_student_id", late)).isNull();
    }

    @Test
    void insertOrder_duplicateOrderCode_rejected() {
        long student = fx.user();
        insertOrder(student, "ORD-TRUNG", 499_000L);
        assertViolates("uq_orders_order_code", () -> insertOrder(fx.user(), "ORD-TRUNG", 499_000L));
    }

    @Test
    void insertOrder_orderCodeDifferingOnlyInCase_rejected() {
        // utf8mb4_0900_ai_ci: codes are unique case-insensitively (stricter than PostgreSQL; codes are app-generated)
        insertOrder(fx.user(), "ORD-ABC", 499_000L);
        assertViolates("uq_orders_order_code", () -> insertOrder(fx.user(), "ord-abc", 499_000L));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void insertOrder_nonPositiveTotal_rejected_BR27(long total) {
        long student = fx.user();
        assertCheckViolated("ck_orders_total", () -> insertOrder(student, "ORD-" + SchemaFixtures.next(), total));
    }

    /**
     * DDD ck_orders_state only holds for 'pending', 'paid' or 'cancelled', so an unknown status breaks it as well as
     * ck_orders_status; MySQL reports only one of them. Either name means "invalid order status".
     */
    @ParameterizedTest
    @ValueSource(strings = {"refunded", "Pending", "PAID", "cancelléd"})
    void insertOrder_invalidStatus_rejected(String status) {
        long student = fx.user();
        assertCheckViolatedAnyOf(() -> insertOrder(student, status, null, null, null),
                "ck_orders_status", "ck_orders_state");
    }

    @Test
    void orderStatusCheck_isEnforcedAndCaseSensitive() {
        assertThat(checkConstraint(jdbc, "orders", "ck_orders_status")).singleElement().asString()
                .startsWith("YES ")
                .contains("cast(`status` as char charset binary)")
                .contains("pending").contains("paid").contains("cancelled");
    }

    @ParameterizedTest
    @ValueSource(strings = {"timeout", "User", "EXPIRED", "replacéd"})
    void insertOrder_invalidCancelReason_rejected(String reason) {
        long student = fx.user();
        // a cancelled row with cancelled_at, so ck_orders_state holds and only the reason list can fail
        assertCheckViolated("ck_orders_cancel_reason", () -> insertOrder(student, "cancelled", reason, null, PAST));
    }

    @Test
    void insertOrder_pendingWithPaidAt_rejected_D25() {
        long student = fx.user();
        assertCheckViolated("ck_orders_state", () -> insertOrder(student, "pending", null, PAST, null));
    }

    @Test
    void insertOrder_pendingWithCancelledAt_rejected_D25() {
        long student = fx.user();
        assertCheckViolated("ck_orders_state", () -> insertOrder(student, "pending", null, null, PAST));
    }

    @Test
    void insertOrder_paidWithoutPaidAt_rejected_D25() {
        long student = fx.user();
        assertCheckViolated("ck_orders_state", () -> insertOrder(student, "paid", null, null, null));
    }

    @Test
    void insertOrder_cancelledWithoutCancelledAt_rejected_D25() {
        long student = fx.user();
        assertCheckViolated("ck_orders_state", () -> insertOrder(student, "cancelled", "user", null, null));
    }

    @Test
    void insertOrder_cancelledWithoutCancelReason_rejected_D25() {
        long student = fx.user();
        assertCheckViolated("ck_orders_state", () -> insertOrder(student, "cancelled", null, null, PAST));
    }

    @Test
    void insertOrder_cancelledWithPaidAt_rejected_D25() {
        long student = fx.user();
        assertCheckViolated("ck_orders_state", () -> insertOrder(student, "cancelled", "user", PAST, PAST));
    }

    @Test
    void insertOrder_unknownStudent_rejected() {
        assertViolates("fk_orders_student_id", () -> insertOrder(-1L, "ORD-" + SchemaFixtures.next(), 499_000L));
    }

    @Test
    void updateOrder_cancel_refreshesUpdatedAt() {
        long id = fx.order(fx.user(), "pending");
        assertUpdatedAtRefreshed("orders", id,
                "status = 'cancelled', cancel_reason = 'user', cancelled_at = CURRENT_TIMESTAMP(6)");
    }

    // ----------------------------------------------------------------------------- orders: one pending (BR-29)

    @Test
    void insertOrder_secondPendingForStudent_rejected_BR29() {
        long student = fx.user();
        fx.order(student, "pending");
        assertViolates("uq_orders_one_pending", () -> fx.order(student, "pending"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"paid", "cancelled"})
    void insertOrder_pendingBesideNonPending_accepted_BR29(String otherStatus) {
        long student = fx.user();
        fx.order(student, otherStatus);
        fx.order(student, "pending");
        fx.order(student, otherStatus);

        assertThat(countWhere("orders", "student_id", student)).isEqualTo(3L);
    }

    @Test
    void insertOrder_pendingForDifferentStudents_accepted_BR29() {
        fx.order(fx.user(), "pending");
        long id = fx.order(fx.user(), "pending");
        assertThat(id).isPositive();
    }

    @ParameterizedTest
    @ValueSource(strings = {"status = 'paid', paid_at = CURRENT_TIMESTAMP(6)",
            "status = 'cancelled', cancel_reason = 'expired', cancelled_at = CURRENT_TIMESTAMP(6)"})
    void updateOrder_leavingPending_freesTheSlot_BR29(String assignments) {
        long student = fx.user();
        long first = fx.order(student, "pending");

        jdbc.sql("UPDATE orders SET " + assignments + " WHERE id = ?").param(first).update();
        long second = fx.order(student, "pending");

        assertThat(pendingKey("orders", "pending_student_id", first)).isNull();
        assertThat(pendingKey("orders", "pending_student_id", second)).isEqualTo(student);
    }

    @Test
    void updateOrder_backToPendingWhileAnotherPending_rejected_BR29() {
        long student = fx.user();
        long cancelled = fx.order(student, "cancelled");
        fx.order(student, "pending");
        assertViolates("uq_orders_one_pending", () -> jdbc.sql("""
                UPDATE orders SET status = 'pending', cancel_reason = NULL, cancelled_at = NULL WHERE id = ?""")
                .param(cancelled).update());
    }

    // ---------------------------------------------------------------------------------------------------- order_items

    @Test
    void insertOrderItem_valid_storesUnitPrice() {
        long id = fx.orderItem(fx.order(fx.user(), "pending"), fx.course());
        assertThat(jdbc.sql("SELECT unit_price FROM order_items WHERE id = ?").param(id).query(Long.class).single())
                .isEqualTo(499_000L);
    }

    @Test
    void insertOrderItem_sameCourseTwiceInOrder_rejected() {
        long order = fx.order(fx.user(), "pending");
        long course = fx.course();
        fx.orderItem(order, course);
        assertViolates("uq_order_items_order_course", () -> fx.orderItem(order, course));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void insertOrderItem_nonPositivePrice_rejected(long price) {
        long order = fx.order(fx.user(), "pending");
        long course = fx.course();
        assertCheckViolated("ck_order_items_price", () -> insertOrderItem(order, course, price));
    }

    @Test
    void insertOrderItem_unknownOrder_rejected() {
        long course = fx.course();
        assertViolates("fk_order_items_order_id", () -> fx.orderItem(-1L, course));
    }

    @Test
    void insertOrderItem_unknownCourse_rejected() {
        long order = fx.order(fx.user(), "pending");
        assertViolates("fk_order_items_course_id", () -> fx.orderItem(order, -1L));
    }

    // ------------------------------------------------------------------------------------------- payment_transactions

    @Test
    void insertPayment_valid_appliesDefaults() {
        long order = fx.order(fx.user(), "pending");
        long id = insertPayment(order, "pending", null);

        var row = jdbc.sql("""
                SELECT status, confirmed_at, handled_by, handled_action, handled_note, handled_at, created_at,
                       updated_at, pending_order_id
                FROM payment_transactions WHERE id = ?""").param(id).query().singleRow();
        assertThat(row.get("status")).isEqualTo("pending");
        assertThat(row).containsEntry("confirmed_at", null).containsEntry("handled_by", null)
                .containsEntry("handled_action", null).containsEntry("handled_note", null)
                .containsEntry("handled_at", null);
        assertThat(row.get("created_at")).isNotNull();
        assertThat(row.get("updated_at")).isNotNull();
        assertThat(row.get("pending_order_id")).isEqualTo(order);
    }

    @Test
    void insertPayment_everyStatusWithItsRequiredColumns_accepted() {
        long order = fx.order(fx.user(), "pending");
        long admin = fx.user();
        insertPayment(order, "pending", null);
        insertPayment(order, "success", PAST);
        insertPayment(order, "failed", null);
        insertPayment(order, "expired", null);
        insertPayment(order, "needs_review", null);
        insertPayment(order, "needs_review", null, admin, "refund_outside", "Hoàn tiền ngoài hệ thống", PAST);
        insertPayment(order, "success", PAST, admin, "activated", "Đã kích hoạt đơn", PAST);

        assertThat(countWhere("payment_transactions", "order_id", order)).isEqualTo(7L);
    }

    @Test
    void insertPayment_duplicateTransactionRef_rejected_NFR04() {
        long order = fx.order(fx.user(), "pending");
        insertPayment(order, "TXN-TRUNG", 499_000L, "failed", null, null, null, null, null);
        assertViolates("uq_payment_ref",
                () -> insertPayment(order, "TXN-TRUNG", 499_000L, "failed", null, null, null, null, null));
    }

    @Test
    void insertPayment_transactionRefDifferingOnlyInCase_rejected() {
        // utf8mb4_0900_ai_ci: refs are unique case-insensitively (stricter than PostgreSQL; refs are app-generated)
        long order = fx.order(fx.user(), "pending");
        insertPayment(order, "TXN-ABC", 499_000L, "failed", null, null, null, null, null);
        assertViolates("uq_payment_ref",
                () -> insertPayment(order, "txn-abc", 499_000L, "failed", null, null, null, null, null));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void insertPayment_nonPositiveAmount_rejected(long amount) {
        long order = fx.order(fx.user(), "pending");
        assertCheckViolated("ck_payment_amount",
                () -> insertPayment(order, "TXN-" + SchemaFixtures.next(), amount, "pending", null, null, null, null,
                        null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"refunded", "Pending", "SUCCESS", "needs-review", "expiréd"})
    void insertPayment_invalidStatus_rejected(String status) {
        long order = fx.order(fx.user(), "pending");
        // no handling and no 'success': ck_payment_handling_status and ck_payment_confirmed hold
        assertCheckViolated("ck_payment_status", () -> insertPayment(order, status, PAST));
    }

    @ParameterizedTest
    @ValueSource(strings = {"refund", "Activated", "REFUND_OUTSIDE", "activatéd"})
    void insertPayment_invalidHandledAction_rejected_D29(String action) {
        long order = fx.order(fx.user(), "pending");
        long admin = fx.user();
        assertCheckViolated("ck_payment_action",
                () -> insertPayment(order, "needs_review", null, admin, action, "Ghi chú", PAST));
    }

    @Test
    void insertPayment_handledWithoutHandledBy_rejected_D29() {
        long order = fx.order(fx.user(), "pending");
        assertCheckViolated("ck_payment_handling_complete",
                () -> insertPayment(order, "needs_review", null, null, "activated", "Ghi chú", PAST));
    }

    @Test
    void insertPayment_handledWithoutAction_rejected_D29() {
        long order = fx.order(fx.user(), "pending");
        long admin = fx.user();
        assertCheckViolated("ck_payment_handling_complete",
                () -> insertPayment(order, "needs_review", null, admin, null, "Ghi chú", PAST));
    }

    @Test
    void insertPayment_handledWithoutNote_rejected_D29() {
        long order = fx.order(fx.user(), "pending");
        long admin = fx.user();
        assertCheckViolated("ck_payment_handling_complete",
                () -> insertPayment(order, "needs_review", null, admin, "activated", null, PAST));
    }

    @Test
    void insertPayment_handledWithoutHandledAt_rejected_D29() {
        long order = fx.order(fx.user(), "pending");
        long admin = fx.user();
        assertCheckViolated("ck_payment_handling_complete",
                () -> insertPayment(order, "needs_review", null, admin, "activated", "Ghi chú", null));
    }

    @Test
    void insertPayment_onlyHandledNote_rejected_D29() {
        long order = fx.order(fx.user(), "pending");
        assertCheckViolated("ck_payment_handling_complete",
                () -> insertPayment(order, "needs_review", null, null, null, "Ghi chú", null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"pending", "failed", "expired"})
    void insertPayment_handledInWrongStatus_rejected(String status) {
        long order = fx.order(fx.user(), "pending");
        long admin = fx.user();
        assertCheckViolated("ck_payment_handling_status",
                () -> insertPayment(order, status, null, admin, "refund_outside", "Ghi chú", PAST));
    }

    @Test
    void insertPayment_successWithoutConfirmedAt_rejected() {
        long order = fx.order(fx.user(), "pending");
        assertCheckViolated("ck_payment_confirmed", () -> insertPayment(order, "success", null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-01-01 00:00:00", "2026-01-02 00:00:00"})
    void insertPayment_qrExpiryNotAfterCreation_rejected(String qrExpiresAt) {
        long order = fx.order(fx.user(), "pending");
        assertCheckViolated("ck_payment_qr_expiry", () -> jdbc.sql("""
                INSERT INTO payment_transactions (order_id, transaction_ref, amount, qr_data, qr_expires_at, created_at)
                VALUES (?, ?, 499000, 'qr', ?, '2026-01-02 00:00:00')""")
                .params(order, "TXN-" + SchemaFixtures.next(), qrExpiresAt).update());
    }

    @Test
    void insertPayment_unknownOrder_rejected() {
        assertViolates("fk_payment_transactions_order_id", () -> insertPayment(-1L, "pending", null));
    }

    @Test
    void insertPayment_unknownHandler_rejected() {
        long order = fx.order(fx.user(), "pending");
        assertViolates("fk_payment_transactions_handled_by",
                () -> insertPayment(order, "needs_review", null, -1L, "activated", "Ghi chú", PAST));
    }

    @Test
    void updatePayment_expire_refreshesUpdatedAt() {
        long id = insertPayment(fx.order(fx.user(), "pending"), "pending", null);
        assertUpdatedAtRefreshed("payment_transactions", id, "status = 'expired'");
    }

    // ---------------------------------------------------------------------- payment_transactions: one pending (BR-16)

    @Test
    void insertPayment_secondPendingForOrder_rejected_BR16() {
        long order = fx.order(fx.user(), "pending");
        insertPayment(order, "pending", null);
        assertViolates("uq_payment_one_pending", () -> insertPayment(order, "pending", null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"success", "failed", "expired", "needs_review"})
    void insertPayment_pendingBesideNonPending_accepted_BR16(String otherStatus) {
        long order = fx.order(fx.user(), "pending");
        String confirmedAt = "success".equals(otherStatus) ? PAST : null;
        insertPayment(order, otherStatus, confirmedAt);
        insertPayment(order, "pending", null);
        insertPayment(order, otherStatus, confirmedAt);

        assertThat(countWhere("payment_transactions", "order_id", order)).isEqualTo(3L);
    }

    @Test
    void insertPayment_pendingForDifferentOrders_accepted_BR16() {
        long student = fx.user();
        insertPayment(fx.order(student, "pending"), "pending", null);
        long id = insertPayment(fx.order(student, "cancelled"), "pending", null);
        assertThat(id).isPositive();
    }

    @ParameterizedTest
    @ValueSource(strings = {"status = 'success', confirmed_at = CURRENT_TIMESTAMP(6)", "status = 'failed'",
            "status = 'expired'", "status = 'needs_review'"})
    void updatePayment_leavingPending_freesTheSlot_BR16(String assignments) {
        long order = fx.order(fx.user(), "pending");
        long first = insertPayment(order, "pending", null);

        jdbc.sql("UPDATE payment_transactions SET " + assignments + " WHERE id = ?").param(first).update();
        long second = insertPayment(order, "pending", null);

        assertThat(pendingKey("payment_transactions", "pending_order_id", first)).isNull();
        assertThat(pendingKey("payment_transactions", "pending_order_id", second)).isEqualTo(order);
    }

    // ----------------------------------------------------------------------------------- ON DELETE (DDD §6 #13–20)

    @Test
    void deleteCart_cascadesItems() {
        long cart = fx.cart(fx.user());
        long item = insertCartItem(cart, fx.course());

        jdbc.sql("DELETE FROM carts WHERE id = ?").param(cart).update();

        assertThat(countWhere("cart_items", "id", item)).isZero();
    }

    @Test
    void deleteUser_owningCart_restricted() {
        long student = fx.user();
        fx.cart(student);
        assertViolates("fk_carts_student_id", () -> deleteUser(student));
    }

    @Test
    void deleteUser_owningOrder_restricted_BR28() {
        long student = fx.user();
        fx.order(student, "cancelled");
        assertViolates("fk_orders_student_id", () -> deleteUser(student));
    }

    @Test
    void deleteUser_whoHandledPayment_restricted_BR28() {
        long admin = fx.user();
        insertPayment(fx.order(fx.user(), "pending"), "needs_review", null, admin, "activated", "Ghi chú", PAST);
        assertViolates("fk_payment_transactions_handled_by", () -> deleteUser(admin));
    }

    @Test
    void deleteCourse_inACart_restricted() {
        long course = fx.course();
        insertCartItem(fx.cart(fx.user()), course);
        assertViolates("fk_cart_items_course_id", () -> deleteCourse(course));
    }

    @Test
    void deleteCourse_inAnOrder_restricted_BR28() {
        long course = fx.course();
        fx.orderItem(fx.order(fx.user(), "pending"), course);
        assertViolates("fk_order_items_course_id", () -> deleteCourse(course));
    }

    @Test
    void deleteOrder_withItems_restricted_BR28() {
        long order = fx.order(fx.user(), "pending");
        fx.orderItem(order, fx.course());
        assertViolates("fk_order_items_order_id", () -> deleteOrder(order));
    }

    @Test
    void deleteOrder_withPayments_restricted_BR28() {
        long order = fx.order(fx.user(), "pending");
        insertPayment(order, "failed", null);
        assertViolates("fk_payment_transactions_order_id", () -> deleteOrder(order));
    }

    // -------------------------------------------------------------------------------------------- schema metadata

    @Test
    void foreignKeys_haveDddNamesAndDeleteRules() {
        assertThat(foreignKeyDeleteRules(jdbc, "carts", "cart_items", "orders", "order_items", "payment_transactions"))
                .containsExactly(
                        "fk_cart_items_cart_id=CASCADE",
                        "fk_cart_items_course_id=RESTRICT",
                        "fk_carts_student_id=RESTRICT",
                        "fk_order_items_course_id=RESTRICT",
                        "fk_order_items_order_id=RESTRICT",
                        "fk_orders_student_id=RESTRICT",
                        "fk_payment_transactions_handled_by=RESTRICT",
                        "fk_payment_transactions_order_id=RESTRICT");
    }

    @Test
    void indexes_haveExpectedColumns() {
        assertThat(indexColumns(jdbc, "carts", "uq_carts_student")).containsExactly("student_id");
        assertThat(indexColumns(jdbc, "cart_items", "uq_cart_items_cart_course")).containsExactly("cart_id", "course_id");
        assertThat(indexColumns(jdbc, "cart_items", "ix_cart_items_course")).containsExactly("course_id");
        assertThat(indexColumns(jdbc, "orders", "uq_orders_order_code")).containsExactly("order_code");
        assertThat(indexColumns(jdbc, "orders", "uq_orders_one_pending")).containsExactly("pending_student_id");
        assertThat(indexColumns(jdbc, "orders", "ix_orders_student_created"))
                .containsExactly("student_id", "created_at DESC");
        assertThat(indexColumns(jdbc, "orders", "ix_orders_pending_created")).containsExactly("status", "created_at");
        assertThat(indexColumns(jdbc, "order_items", "uq_order_items_order_course"))
                .containsExactly("order_id", "course_id");
        assertThat(indexColumns(jdbc, "order_items", "ix_order_items_course")).containsExactly("course_id");
        assertThat(indexColumns(jdbc, "payment_transactions", "uq_payment_ref")).containsExactly("transaction_ref");
        assertThat(indexColumns(jdbc, "payment_transactions", "uq_payment_one_pending"))
                .containsExactly("pending_order_id");
        assertThat(indexColumns(jdbc, "payment_transactions", "ix_payment_order_created"))
                .containsExactly("order_id", "created_at DESC");
        assertThat(indexColumns(jdbc, "payment_transactions", "ix_payment_review_queue"))
                .containsExactly("status", "handled_at", "created_at");
        assertThat(indexColumns(jdbc, "payment_transactions", "ix_payment_pending_expiry"))
                .containsExactly("status", "qr_expires_at");
        assertThat(indexColumns(jdbc, "payment_transactions", "ix_payment_handled_by")).containsExactly("handled_by");
    }

    @Test
    void pendingColumns_areStoredGenerated() {
        assertThat(generatedColumn("orders", "pending_student_id")).containsExactly("STORED GENERATED", "bigint");
        assertThat(generatedColumn("payment_transactions", "pending_order_id"))
                .containsExactly("STORED GENERATED", "bigint");
    }

    @Test
    void columns_matchDddPlusGeneratedColumnsOnly() {
        assertThat(columns("carts")).containsExactly("id", "student_id", "created_at", "updated_at");
        assertThat(columns("cart_items")).containsExactly("id", "cart_id", "course_id", "added_at");
        assertThat(columns("orders")).containsExactly("id", "student_id", "order_code", "total_amount", "status",
                "cancel_reason", "created_at", "paid_at", "cancelled_at", "updated_at", "pending_student_id");
        assertThat(columns("order_items")).containsExactly("id", "order_id", "course_id", "unit_price");
        assertThat(columns("payment_transactions")).containsExactly("id", "order_id", "transaction_ref", "amount",
                "qr_data", "qr_expires_at", "status", "confirmed_at", "handled_by", "handled_action", "handled_note",
                "handled_at", "created_at", "updated_at", "pending_order_id");
    }

    // ------------------------------------------------------------------------------------------------------ helpers

    private long insertCartItem(long cartId, long courseId) {
        return fx.insert(jdbc.sql("INSERT INTO cart_items (cart_id, course_id) VALUES (?, ?)").params(cartId, courseId));
    }

    private long insertOrder(long studentId, String orderCode, long totalAmount) {
        return fx.insert(jdbc.sql("INSERT INTO orders (student_id, order_code, total_amount) VALUES (?, ?, ?)")
                .params(studentId, orderCode, totalAmount));
    }

    private long insertOrder(long studentId, String status, String cancelReason, String paidAt, String cancelledAt) {
        return fx.insert(jdbc.sql("""
                INSERT INTO orders (student_id, order_code, total_amount, status, cancel_reason, paid_at, cancelled_at)
                VALUES (?, ?, 499000, ?, ?, ?, ?)""")
                .params(studentId, "ORD-" + SchemaFixtures.next(), status, cancelReason, paidAt, cancelledAt));
    }

    private long insertOrderItem(long orderId, long courseId, long unitPrice) {
        return fx.insert(jdbc.sql("INSERT INTO order_items (order_id, course_id, unit_price) VALUES (?, ?, ?)")
                .params(orderId, courseId, unitPrice));
    }

    private long insertPayment(long orderId, String status, String confirmedAt) {
        return insertPayment(orderId, status, confirmedAt, null, null, null, null);
    }

    private long insertPayment(long orderId, String status, String confirmedAt, Long handledBy, String action,
                               String note, String handledAt) {
        return insertPayment(orderId, "TXN-" + SchemaFixtures.next(), 499_000L, status, confirmedAt, handledBy, action,
                note, handledAt);
    }

    private long insertPayment(long orderId, String ref, long amount, String status, String confirmedAt,
                               Long handledBy, String action, String note, String handledAt) {
        return fx.insert(jdbc.sql("""
                INSERT INTO payment_transactions (order_id, transaction_ref, amount, qr_data, qr_expires_at, status,
                                                  confirmed_at, handled_by, handled_action, handled_note, handled_at)
                VALUES (?, ?, ?, '00020101021238570010A000000727', ?, ?, ?, ?, ?, ?, ?)""")
                .params(orderId, ref, amount, FUTURE, status, confirmedAt, handledBy, action, note, handledAt));
    }

    private void deleteUser(long id) {
        jdbc.sql("DELETE FROM users WHERE id = ?").param(id).update();
    }

    private void deleteCourse(long id) {
        jdbc.sql("DELETE FROM courses WHERE id = ?").param(id).update();
    }

    private void deleteOrder(long id) {
        jdbc.sql("DELETE FROM orders WHERE id = ?").param(id).update();
    }

    private long countWhere(String table, String column, long value) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?").param(value)
                .query(Long.class).single();
    }

    /** Value of a generated pending_* column (null when the row is not pending). */
    private Object pendingKey(String table, String column, long id) {
        return jdbc.sql("SELECT " + column + " FROM " + table + " WHERE id = ?").param(id).query().singleRow()
                .get(column);
    }

    /** {@code EXTRA} and {@code DATA_TYPE} of a column. */
    private List<String> generatedColumn(String table, String column) {
        var row = jdbc.sql("""
                SELECT EXTRA, DATA_TYPE FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?""")
                .params(table, column).query().singleRow();
        return List.of((String) row.get("EXTRA"), (String) row.get("DATA_TYPE"));
    }

    private List<String> columns(String table) {
        return jdbc.sql("""
                SELECT COLUMN_NAME FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION""")
                .param(table).query(String.class).list();
    }

    /** Backdates updated_at, runs the update and asserts ON UPDATE CURRENT_TIMESTAMP(6) moved it forward. */
    private void assertUpdatedAtRefreshed(String table, long id, String assignments) {
        jdbc.sql("UPDATE " + table + " SET updated_at = '2020-01-01 00:00:00' WHERE id = ?").param(id).update();
        jdbc.sql("UPDATE " + table + " SET " + assignments + " WHERE id = ?").param(id).update();

        LocalDateTime updatedAt = jdbc.sql("SELECT updated_at FROM " + table + " WHERE id = ?")
                .param(id).query(LocalDateTime.class).single();
        assertThat(updatedAt).isAfter(LocalDateTime.of(2020, 1, 2, 0, 0));
    }
}
