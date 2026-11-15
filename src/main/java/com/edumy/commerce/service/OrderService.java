package com.edumy.commerce.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.edumy.authoring.api.CourseAccessApi;
import com.edumy.authoring.api.CourseQueryApi;
import com.edumy.authoring.api.CourseSummary;
import com.edumy.commerce.config.PaymentProperties;
import com.edumy.commerce.dto.response.OrderDetailResponse;
import com.edumy.commerce.entity.CancelReason;
import com.edumy.commerce.entity.Cart;
import com.edumy.commerce.entity.Order;
import com.edumy.commerce.entity.OrderItem;
import com.edumy.commerce.integration.PaymentGatewayException;
import com.edumy.commerce.repository.CartRepository;
import com.edumy.commerce.repository.OrderRepository;
import com.edumy.commerce.service.PendingPaymentSettler.Settlement;
import com.edumy.identity.api.UserQueryApi;
import com.edumy.learning.api.EnrollmentApi;
import com.edumy.shared.error.BusinessException;
import com.edumy.shared.error.ErrorCode;
import com.edumy.shared.error.FieldViolation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Order use cases (P11-b, ADR-011): UC-S04 T1 create order and UC-S16 cancel. Both run in a {@link TransactionTemplate}
 * with {@code edumy.payment.tx-timeout} because they may ask the payment provider while they hold the student's locks
 * (derived from UC-S04 / UC-S16, DDD §11 unavailable).
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    private static final int CODE_ATTEMPTS = 5;

    private final UserQueryApi users;
    private final CartRepository carts;
    private final OrderRepository orders;
    private final CourseQueryApi courses;
    private final CourseAccessApi courseAccess;
    private final EnrollmentApi enrollments;
    private final PendingPaymentSettler settler;
    private final CommerceCodeGenerator codes;
    private final StudentPaymentLock studentLock;
    private final OrderQueryService queries;
    private final TransactionTemplate tx;
    private final Clock clock;

    public OrderService(UserQueryApi users, CartRepository carts, OrderRepository orders, CourseQueryApi courses,
                        CourseAccessApi courseAccess, EnrollmentApi enrollments, PendingPaymentSettler settler,
                        CommerceCodeGenerator codes, StudentPaymentLock studentLock, OrderQueryService queries,
                        PaymentProperties properties,
                        PlatformTransactionManager transactionManager, Clock clock) {
        this.users = users;
        this.carts = carts;
        this.orders = orders;
        this.courses = courses;
        this.courseAccess = courseAccess;
        this.enrollments = enrollments;
        this.settler = settler;
        this.codes = codes;
        this.studentLock = studentLock;
        this.queries = queries;
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setTimeout(properties.txTimeoutSeconds());
        this.clock = clock;
    }

    /** {@code created}: a new order (201); otherwise the identical pending order is returned (200, UC-S04 E3). */
    public record CreateOutcome(OrderDetailResponse order, boolean created) {
    }

    private record T1Result(String orderCode, boolean created, boolean previousPaid) {
    }

    /**
     * UC-S04 (T1). Outside the transaction: D-02 (unverified email → 403 {@code EMAIL_NOT_VERIFIED}). Inside, in this
     * order:
     * <ol>
     *   <li>the student's cart row {@code FOR UPDATE} — every T1 of one student is serialised on it (UC-S04 E3, AC-21);
     *   no cart → 422 {@code CART_EMPTY};</li>
     *   <li>step 1 / D-26: every line re-validated (not published → {@code course_unavailable}, free now →
     *   {@code now_free}, owned → {@code already_owned}, instructor relation whatever the role → {@code own_course},
     *   BR-32); prices taken now (BR-12, D-14); nothing purchasable → 422 {@code CART_EMPTY} (E1, AC-18);</li>
     *   <li>step 3 / Q2: {@code expectedCourseIds} / {@code expectedTotalAmount} differ → 409
     *   {@code ORDER_CART_CHANGED}, nothing touched;</li>
     *   <li>step 2 / D-24: the pending order {@code FOR UPDATE}; same courses and prices → returned as it is (Q1, E3);
     *   otherwise its pending attempt is looked up: paid → recorded, committed, then 409 {@code ORDER_ALREADY_PAID}
     *   ("quay lại giỏ"); provider down → 503 {@code PAYMENT_PROVIDER_UNAVAILABLE}, nothing changed; else the attempt is
     *   closed and the order cancelled as {@code replaced} (flushed before the insert, uq_orders_one_pending);</li>
     *   <li>step 5: new pending order with the lines and their locked prices, total = Σ (BR-27). The cart is kept
     *   (post-condition).</li>
     * </ol>
     */
    public CreateOutcome create(Long studentId, CreateOrderCommand command) {
        if (!users.isEmailVerified(studentId)) {
            throw new BusinessException(ErrorCode.EMAIL_NOT_VERIFIED); // D-02, UC-S04 E2
        }
        T1Result result = tx.execute(status -> createLocked(studentId, command));
        if (result.previousPaid()) {
            throw new BusinessException(ErrorCode.ORDER_ALREADY_PAID); // committed before (ADR-011)
        }
        return new CreateOutcome(queries.getMine(studentId, result.orderCode()), result.created());
    }

    private T1Result createLocked(Long studentId, CreateOrderCommand command) {
        Instant now = clock.instant();
        Cart cart = carts.findByStudentIdForUpdate(studentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CART_EMPTY));
        List<Long> cartCourseIds = cart.courseIds();
        Map<Long, Long> lines = new LinkedHashMap<>();
        Map<Long, String> excluded = new LinkedHashMap<>();
        revalidate(studentId, cartCourseIds, lines, excluded);
        if (lines.isEmpty()) {
            throw new BusinessException(ErrorCode.CART_EMPTY); // UC-S04 E1, AC-18
        }
        checkExpected(command, lines, excluded);

        Optional<Order> pending = orders.findPendingByStudentIdForUpdate(studentId);
        if (pending.isPresent()) {
            Order old = pending.get();
            if (sameLines(old, lines)) {
                log.info("Order reused (identical pending order): orderCode={}, studentId={}", old.getOrderCode(),
                        studentId);
                return new T1Result(old.getOrderCode(), false, false);
            }
            if (settle(old) == Settlement.PAID) {
                log.info("Order not replaced, the lookup found it paid: orderCode={}", old.getOrderCode());
                return new T1Result(old.getOrderCode(), false, true);
            }
            old.cancel(CancelReason.REPLACED, now);
            orders.flush(); // before the insert: uq_orders_one_pending (IDENTITY inserts run at persist time)
            log.info("Order replaced: orderCode={}, studentId={}", old.getOrderCode(), studentId);
        }
        Order order = Order.create(studentId, newOrderCode(now),
                lines.entrySet().stream().map(e -> new Order.Line(e.getKey(), e.getValue())).toList(), now);
        orders.saveAndFlush(order);
        log.info("Order created: orderCode={}, studentId={}, totalAmount={}, courseIds={}", order.getOrderCode(),
                studentId, order.getTotalAmount(), lines.keySet());
        return new T1Result(order.getOrderCode(), true, false);
    }

    /** D-26 re-validation in the cart order; reasons as in {@code GET /cart} (precedence kept). 3 queries. */
    private void revalidate(Long studentId, List<Long> cartCourseIds, Map<Long, Long> lines,
                            Map<Long, String> excluded) {
        if (cartCourseIds.isEmpty()) {
            return;
        }
        Map<Long, CourseSummary> summaries = courses.findSummaries(cartCourseIds);
        Set<Long> owned = enrollments.findOwnedCourseIds(studentId, cartCourseIds);
        Set<Long> instructed = courseAccess.findInstructorRelationCourseIds(studentId, cartCourseIds);
        for (Long courseId : cartCourseIds) {
            CourseSummary course = summaries.get(courseId);
            if (course == null || !course.published()) {
                excluded.put(courseId, "course_unavailable"); // BR-31
            } else if (owned.contains(courseId)) {
                excluded.put(courseId, "already_owned"); // BR-10
            } else if (instructed.contains(courseId)) {
                excluded.put(courseId, "own_course"); // BR-32, relation whatever the role
            } else if (course.price() <= 0) {
                excluded.put(courseId, "now_free"); // BR-09
            } else {
                lines.put(courseId, course.price()); // BR-12: locked now
            }
        }
    }

    /** UC-S04 step 3 / Q2: what the student confirmed must still be what would be ordered. */
    private static void checkExpected(CreateOrderCommand command, Map<Long, Long> lines, Map<Long, String> excluded) {
        List<FieldViolation> errors = new ArrayList<>();
        if (command.expectedCourseIds() != null) {
            Set<Long> expected = new LinkedHashSet<>(command.expectedCourseIds());
            for (Long courseId : expected) {
                if (!lines.containsKey(courseId)) {
                    errors.add(new FieldViolation("expectedCourseIds",
                            "Khóa học #" + courseId + ": " + reasonText(excluded.get(courseId))));
                }
            }
            for (Long courseId : lines.keySet()) {
                if (!expected.contains(courseId)) {
                    errors.add(new FieldViolation("expectedCourseIds",
                            "Khóa học #" + courseId + ": mới có trong giỏ hàng"));
                }
            }
        }
        long total = lines.values().stream().mapToLong(Long::longValue).sum();
        if (command.expectedTotalAmount() != null && command.expectedTotalAmount() != total) {
            errors.add(new FieldViolation("expectedTotalAmount", "Tổng tiền hiện tại là " + total + " đồng"));
        }
        if (!errors.isEmpty()) {
            throw BusinessException.withErrors(ErrorCode.ORDER_CART_CHANGED, errors);
        }
    }

    private static String reasonText(String reason) {
        if (reason == null) {
            return "không còn trong giỏ hàng";
        }
        return switch (reason) {
            case "course_unavailable" -> "khóa học không còn được bán";
            case "already_owned" -> "bạn đã sở hữu khóa học này";
            case "own_course" -> "bạn là giảng viên của khóa học này";
            case "now_free" -> "khóa học đã chuyển sang miễn phí";
            default -> reason;
        };
    }

    /** Q1: same courses with the same locked prices. */
    private static boolean sameLines(Order order, Map<Long, Long> lines) {
        Map<Long, Long> existing = new LinkedHashMap<>();
        for (OrderItem item : order.getItems()) {
            existing.put(item.getCourseId(), item.getUnitPrice());
        }
        return existing.equals(lines);
    }

    private String newOrderCode(Instant now) {
        for (int i = 0; i < CODE_ATTEMPTS; i++) {
            String code = codes.newOrderCode(now);
            if (!orders.existsByOrderCode(code)) {
                return code;
            }
        }
        throw new BusinessException(ErrorCode.DATA_CONFLICT);
    }

    /**
     * UC-S16 cancel by the student. Order id read as a scalar, then the order {@code FOR UPDATE} (R1); unknown or
     * another student's → 404 (identical); not pending → 409 {@code DATA_CONFLICT}. Step 1: the pending attempt is
     * looked up — paid → recorded, committed, 409 {@code ORDER_ALREADY_PAID} (E1); provider down → 503, the order stays
     * pending (E2); otherwise the attempt is closed and the order cancelled as {@code user} (step 2). The cart is not
     * touched (step 3). Returns the order as the student now sees it (UC-S07).
     */
    public OrderDetailResponse cancel(Long studentId, String orderCode) {
        boolean paid = Boolean.TRUE.equals(tx.execute(status -> cancelLocked(studentId, orderCode)));
        if (paid) {
            throw new BusinessException(ErrorCode.ORDER_ALREADY_PAID); // committed before (ADR-011)
        }
        return queries.getMine(studentId, orderCode);
    }

    private boolean cancelLocked(Long studentId, String orderCode) {
        Long orderId = orders.findIdByOrderCodeAndStudentId(orderCode, studentId)
                .orElseThrow(BusinessException::notFound);
        studentLock.lock(studentId); // ADR-011: the student's cart row first (P11-fix-a)
        Order order = orders.findByIdForUpdate(orderId).orElseThrow(BusinessException::notFound);
        if (!order.isPending()) {
            throw new BusinessException(ErrorCode.DATA_CONFLICT);
        }
        if (settle(order) == Settlement.PAID) {
            log.info("Order not cancelled, the lookup found it paid: orderCode={}", order.getOrderCode());
            return true;
        }
        order.cancel(CancelReason.USER, clock.instant());
        log.info("Order cancelled by the student: orderCode={}, studentId={}", order.getOrderCode(), studentId);
        return false;
    }

    /** Provider down → 503, the caller's transaction rolls back (UC-S16 E2). */
    private Settlement settle(Order order) {
        try {
            return settler.settle(order);
        } catch (PaymentGatewayException e) {
            log.warn("Payment provider unavailable: orderCode={}, error={}", order.getOrderCode(), PaymentGatewayException.describe(e));
            throw new BusinessException(ErrorCode.PAYMENT_PROVIDER_UNAVAILABLE);
        }
    }
}
