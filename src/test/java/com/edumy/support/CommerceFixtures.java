package com.edumy.support;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

/**
 * Committed commerce rows for {@link MySqlSpringBootTest} tests (P11): carts, orders with lines, payment attempts —
 * plain SQL, no entity, every CHECK of V3 satisfied. {@link #deleteAll()} removes everything of the tracked students,
 * including what the API created for them (orders, attempts, purchase enrollments, cart lines). Call it in
 * {@code @AfterEach} <b>before</b> {@code CourseFixtures.deleteAll()} and {@code TestUsers.deleteAll()}.
 */
public class CommerceFixtures {

    private final JdbcClient jdbc;
    private final List<Long> students = new ArrayList<>();

    public CommerceFixtures(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Everything of {@code studentId} (cart, orders, attempts, enrollments) is deleted by {@link #deleteAll()}. */
    public void track(long studentId) {
        students.add(studentId);
    }

    /** The student's cart with one line per course (created if missing). Returns the cart id. */
    public long cart(long studentId, long... courseIds) {
        track(studentId);
        jdbc.sql("INSERT INTO carts (student_id) VALUES (?) ON DUPLICATE KEY UPDATE student_id = student_id")
                .param(studentId).update();
        long cartId = jdbc.sql("SELECT id FROM carts WHERE student_id = ?").param(studentId).query(Long.class).single();
        for (long courseId : courseIds) {
            jdbc.sql("INSERT INTO cart_items (cart_id, course_id) VALUES (?, ?)").params(cartId, courseId).update();
        }
        return cartId;
    }

    /** Course ids of the student's cart lines, in added order. */
    public List<Long> cartCourseIds(long studentId) {
        return jdbc.sql("""
                        SELECT ci.course_id FROM cart_items ci JOIN carts c ON c.id = ci.cart_id
                        WHERE c.student_id = ? ORDER BY ci.id""")
                .param(studentId).query(Long.class).list();
    }

    /** A new random order code (≤ 30 characters, distinct from the generated format). */
    public static String newOrderCode() {
        return "ORD-T-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase();
    }

    /** A new random transaction reference. */
    public static String newReference() {
        return "REF-T-" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
    }

    /** Builder of one order (lines in insertion order, total = Σ prices). */
    public OrderBuilder order(long studentId) {
        track(studentId);
        return new OrderBuilder(studentId);
    }

    public final class OrderBuilder {

        private final long studentId;
        private final Map<Long, Long> lines = new LinkedHashMap<>();
        private String code = newOrderCode();
        private String status = "pending";
        private String cancelReason;
        private Instant createdAt = Instant.now().minusSeconds(60);

        private OrderBuilder(long studentId) {
            this.studentId = studentId;
        }

        public OrderBuilder line(long courseId, long unitPrice) {
            lines.put(courseId, unitPrice);
            return this;
        }

        public OrderBuilder code(String orderCode) {
            this.code = orderCode;
            return this;
        }

        public OrderBuilder createdAt(Instant instant) {
            this.createdAt = instant;
            return this;
        }

        /** Cancelled with {@code reason} (user / replaced / expired). */
        public OrderBuilder cancelled(String reason) {
            this.status = "cancelled";
            this.cancelReason = reason;
            return this;
        }

        public OrderBuilder paid() {
            this.status = "paid";
            return this;
        }

        /** Inserts the order and its lines; returns the order. */
        public FixtureOrder insert() {
            long total = lines.values().stream().mapToLong(Long::longValue).sum();
            Timestamp created = Timestamp.from(createdAt);
            long id = CommerceFixtures.insert(jdbc.sql("""
                            INSERT INTO orders (student_id, order_code, total_amount, status, cancel_reason, created_at,
                                                paid_at, cancelled_at)
                            VALUES (?, ?, ?, ?, ?, ?, CASE WHEN ? = 'paid' THEN ? END,
                                    CASE WHEN ? = 'cancelled' THEN ? END)""")
                    .params(studentId, code, total, status, cancelReason, created, status, created, status, created));
            lines.forEach((courseId, price) ->
                    jdbc.sql("INSERT INTO order_items (order_id, course_id, unit_price) VALUES (?, ?, ?)")
                            .params(id, courseId, price).update());
            return new FixtureOrder(id, code, studentId, total);
        }
    }

    public record FixtureOrder(long id, String code, long studentId, long total) {
    }

    /**
     * A QR attempt of {@code order} in {@code status} (pending / failed / expired / success / needs_review), amount =
     * the order total, created at {@code createdAt}, QR valid until {@code qrExpiresAt}. Returns the attempt id.
     */
    public long payment(FixtureOrder order, String reference, String status, Instant createdAt, Instant qrExpiresAt) {
        return insert(jdbc.sql("""
                        INSERT INTO payment_transactions (order_id, transaction_ref, amount, qr_data, qr_expires_at,
                                                          status, confirmed_at, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, CASE WHEN ? = 'success' THEN ? END, ?)""")
                .params(order.id(), reference, order.total(), "FAKEQR|EDUMY|" + reference + "|" + order.total(),
                        Timestamp.from(qrExpiresAt), status, status, Timestamp.from(createdAt),
                        Timestamp.from(createdAt)));
    }

    /** A pending attempt created a minute ago, valid 14 more minutes. */
    public long pendingPayment(FixtureOrder order, String reference) {
        Instant now = Instant.now();
        return payment(order, reference, "pending", now.minusSeconds(60), now.plusSeconds(14 * 60));
    }

    /** One column of the attempt ({@code null} allowed). */
    public <T> T paymentColumn(String reference, String column, Class<T> type) {
        return jdbc.sql("SELECT " + column + " FROM payment_transactions WHERE transaction_ref = ?")
                .param(reference).query((rs, n) -> rs.getObject(1, type)).list().getFirst();
    }

    /** One column of the order ({@code null} allowed). */
    public <T> T orderColumn(String orderCode, String column, Class<T> type) {
        return jdbc.sql("SELECT " + column + " FROM orders WHERE order_code = ?").param(orderCode)
                .query((rs, n) -> rs.getObject(1, type)).list().getFirst();
    }

    /** Enrollments of the student: course id → source. */
    public Map<Long, String> enrollments(long studentId) {
        Map<Long, String> rows = new LinkedHashMap<>();
        jdbc.sql("SELECT course_id, enroll_source FROM enrollments WHERE student_id = ? ORDER BY course_id")
                .param(studentId)
                .query((rs, n) -> rows.put(rs.getLong(1), rs.getString(2)))
                .list();
        return rows;
    }

    /** Deletes every row of the tracked students, children first. */
    public void deleteAll() {
        for (Long id : students) {
            jdbc.sql("""
                    DELETE FROM lesson_progresses WHERE enrollment_id IN (SELECT id FROM enrollments WHERE student_id = ?)""")
                    .param(id).update();
            jdbc.sql("DELETE FROM certificates WHERE enrollment_id IN (SELECT id FROM enrollments WHERE student_id = ?)")
                    .param(id).update();
            jdbc.sql("DELETE FROM enrollments WHERE student_id = ?").param(id).update();
            jdbc.sql("""
                    DELETE FROM payment_transactions WHERE order_id IN (SELECT id FROM orders WHERE student_id = ?)""")
                    .param(id).update();
            jdbc.sql("DELETE FROM order_items WHERE order_id IN (SELECT id FROM orders WHERE student_id = ?)")
                    .param(id).update();
            jdbc.sql("DELETE FROM orders WHERE student_id = ?").param(id).update();
            jdbc.sql("DELETE FROM carts WHERE student_id = ?").param(id).update(); // items cascade
        }
        students.clear();
    }

    private static long insert(JdbcClient.StatementSpec statement) {
        KeyHolder keys = new GeneratedKeyHolder();
        statement.update(keys);
        return keys.getKey().longValue();
    }
}
