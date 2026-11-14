package com.edumy.commerce.service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import com.edumy.authoring.api.CourseQueryApi;
import com.edumy.authoring.api.CourseSummary;
import com.edumy.commerce.dto.request.CreateOrderRequest;
import com.edumy.commerce.dto.response.CartResponse;
import com.edumy.commerce.dto.response.OrderDetailResponse;
import com.edumy.commerce.entity.Order;
import com.edumy.commerce.entity.OrderItem;
import com.edumy.commerce.mapper.OrderMapper;
import com.edumy.commerce.repository.OrderRepository;
import com.edumy.commerce.repository.PaymentTransactionRepository;
import com.edumy.identity.api.UserQueryApi;
import com.edumy.shared.error.BusinessException;
import com.edumy.shared.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Order use cases, <b>phase-1 STUBS</b> (ADR-005, docs/ai/06): UC-S04 T1 and UC-S16 only read and compute, they never
 * write ({@code readOnly = true}, no repository save). Phase 2 replaces the bodies with the real transactions
 * (docs/ai/01 §1.11 T1: lock the cart, D-24 replace the old pending order, insert order + lines) and the methods become
 * write transactions; the controllers stay.
 */
@Service
public class OrderService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private final UserQueryApi users;
    private final CartQueryService carts;
    private final OrderRepository orders;
    private final PaymentTransactionRepository payments;
    private final CourseQueryApi courses;
    private final OrderMapper mapper;
    private final Clock clock;

    public OrderService(UserQueryApi users, CartQueryService carts, OrderRepository orders,
                        PaymentTransactionRepository payments, CourseQueryApi courses, OrderMapper mapper,
                        Clock clock) {
        this.users = users;
        this.carts = carts;
        this.orders = orders;
        this.payments = payments;
        this.courses = courses;
        this.mapper = mapper;
        this.clock = clock;
    }

    /**
     * UC-S04 preview. Real checks: email verified (E2, D-02 → 403 {@code EMAIL_NOT_VERIFIED}); the lines of the caller's
     * cart re-validated like D-26 (step 1: hidden / owned / own / now-free lines are left out, the reasons stay visible
     * in {@code GET /cart}); no purchasable line left → 422 {@code CART_EMPTY} (E1). Sample part: the code
     * {@code ORD-<yyyyMMdd>-STUB} (never stored), status {@code pending}, lines at the current price (BR-12), total =
     * Σ prices (BR-27), no payment attempt. An existing pending order is not touched (D-24 happens in phase 2).
     */
    @Transactional(readOnly = true)
    public OrderDetailResponse create(Long studentId, CreateOrderRequest request) {
        if (!users.isEmailVerified(studentId)) {
            throw new BusinessException(ErrorCode.EMAIL_NOT_VERIFIED);
        }
        List<CartResponse.Item> lines = carts.getCart(studentId).items().stream()
                .filter(CartResponse.Item::purchasable)
                .toList();
        if (lines.isEmpty()) {
            throw new BusinessException(ErrorCode.CART_EMPTY);
        }
        Instant now = clock.instant();
        return mapper.toPreview("ORD-" + DAY.format(now) + "-STUB", now, lines);
    }

    /**
     * UC-S16 preview. The order is looked up by code AND student id: another student's code and an unknown code give the
     * same 404. Precondition: status {@code pending}, else 409 {@code DATA_CONFLICT} (the code {@code Order.cancel}
     * throws). Returns the order as it would look cancelled; nothing is saved and no provider is asked (step 1).
     */
    @Transactional(readOnly = true)
    public OrderDetailResponse cancel(Long studentId, String orderCode) {
        Order order = orders.findWithItemsByOrderCodeAndStudentId(orderCode, studentId)
                .orElseThrow(BusinessException::notFound);
        if (!order.isPending()) {
            throw new BusinessException(ErrorCode.DATA_CONFLICT);
        }
        Map<Long, CourseSummary> summaries = courses.findSummaries(
                order.getItems().stream().map(OrderItem::getCourseId).toList());
        OrderDetailResponse current = mapper.toDetail(order, summaries,
                payments.findByOrderIdOrderByCreatedAtDescIdDesc(order.getId()));
        return mapper.toCancelledPreview(current, clock.instant());
    }
}
