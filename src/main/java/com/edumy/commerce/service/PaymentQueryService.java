package com.edumy.commerce.service;

import com.edumy.authoring.api.CourseQueryApi;
import com.edumy.authoring.api.CourseSummary;
import com.edumy.commerce.dto.response.AdminPaymentDetailResponse;
import com.edumy.commerce.dto.response.AdminPaymentResponse;
import com.edumy.commerce.dto.response.PaymentStatusResponse;
import com.edumy.commerce.entity.Order;
import com.edumy.commerce.entity.PaymentStatus;
import com.edumy.commerce.entity.PaymentTransaction;
import com.edumy.commerce.integration.PaymentGateway;
import com.edumy.commerce.integration.PaymentGatewayException;
import com.edumy.commerce.integration.PaymentLookup;
import com.edumy.commerce.mapper.PaymentMapper;
import com.edumy.commerce.repository.OrderRepository;
import com.edumy.commerce.repository.PaymentTransactionRepository;
import com.edumy.commerce.repository.PaymentTransactionRepository.AdminPaymentView;
import com.edumy.identity.api.UserQueryApi;
import com.edumy.identity.api.UserSummary;
import com.edumy.learning.api.EnrollmentApi;
import com.edumy.shared.error.BusinessException;
import com.edumy.shared.jpa.CodeEnum;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * UC-S05 polling (own order only) and UC-A14 (admin list). Polling = order + pending attempt, falling back to the
 * latest one (3 statements at most); admin list = page + count + names.
 */
@Service
@Transactional(readOnly = true)
public class PaymentQueryService {

    private final OrderRepository orders;
    private final PaymentTransactionRepository payments;
    private final UserQueryApi users;
    private final PaymentMapper mapper;
    private final CourseQueryApi courses;
    private final EnrollmentApi enrollments;
    private final PaymentGateway gateway;
    private final TransactionTemplate readOnly;

    public PaymentQueryService(OrderRepository orders, PaymentTransactionRepository payments, UserQueryApi users,
                               PaymentMapper mapper, CourseQueryApi courses, EnrollmentApi enrollments,
                               PaymentGateway gateway, PlatformTransactionManager transactionManager) {
        this.orders = orders;
        this.payments = payments;
        this.users = users;
        this.mapper = mapper;
        this.courses = courses;
        this.enrollments = enrollments;
        this.gateway = gateway;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
    }

    /**
     * The pending attempt of the caller's order (for a paid order: the attempt that paid it, P11-fix-d), else its latest
     * attempt. Unknown code, another student's code, and an
     * order that has no attempt yet (nothing to poll: the QR is created by {@code POST …/payments}, UC-S05 step 1) all
     * give 404 {@code RESOURCE_NOT_FOUND}.
     */
    public PaymentStatusResponse current(Long studentId, String orderCode) {
        Order order = orders.findByOrderCodeAndStudentId(orderCode, studentId)
                .orElseThrow(BusinessException::notFound);
        PaymentTransaction payment = (order.isPaid()
                ? payments.findFirstByOrderIdAndStatusOrderByCreatedAtDescIdDesc(order.getId(), PaymentStatus.SUCCESS)
                : payments.findPendingByOrderId(order.getId()))
                .or(() -> payments.findFirstByOrderIdOrderByCreatedAtDescIdDesc(order.getId()))
                .orElseThrow(BusinessException::notFound);
        return mapper.toStatus(order, payment);
    }

    /** UC-A14: newest first, or the unhandled reconciliation queue (oldest first) with {@code awaitingReview}. */
    public Page<AdminPaymentResponse> search(PaymentSearchCriteria filter, Pageable pageable) {
        Pageable unsorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
        Page<AdminPaymentView> page;
        if (filter.awaitingReview()) {
            page = payments.findReviewQueue(unsorted);
        } else {
            PaymentStatus status = filter.status() == null ? null
                    : CodeEnum.fromCode(PaymentStatus.class, filter.status());
            page = payments.searchForAdmin(status, unsorted);
        }
        List<Long> userIds = new ArrayList<>();
        page.forEach(p -> {
            userIds.add(p.getStudentId());
            userIds.add(p.getHandledBy());
        });
        Map<Long, UserSummary> names = users.findSummaries(userIds);
        return page.map(payment -> mapper.toAdmin(payment, names));
    }

    /** UC-A18 (P11-d): one attempt as an admin row; unknown id → 404. */
    public AdminPaymentResponse getAdmin(Long paymentId) {
        AdminPaymentView view = payments.findAdminViewById(paymentId).orElseThrow(BusinessException::notFound);
        Map<Long, UserSummary> names = users.findSummaries(java.util.Arrays.asList(view.getStudentId(),
                view.getHandledBy()));
        return mapper.toAdmin(view, names);
    }

    /**
     * UC-A18 step 1 (P11 Q4): the attempt, its order lines (locked prices, titles read now), the courses the student
     * already owns, and the amount the provider reports as paid now ({@code null} if nothing paid or the provider is
     * unreachable — the page still opens). Unknown id → 404.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AdminPaymentDetailResponse getAdminDetail(Long paymentId) {
        // review P11-R-06: the database part runs in its own read-only transaction, closed before the provider call
        AdminPaymentDetailResponse withoutAmount = readOnly.execute(status -> {
            AdminPaymentResponse payment = getAdmin(paymentId);
            Order order = orders.findById(payment.orderId()).orElseThrow(BusinessException::notFound);
            List<Long> courseIds = order.courseIds();
            Map<Long, CourseSummary> titles = courses.findSummaries(courseIds);
            List<AdminPaymentDetailResponse.Item> items = order.getItems().stream()
                    .map(item -> new AdminPaymentDetailResponse.Item(item.getCourseId(),
                            titles.containsKey(item.getCourseId()) ? titles.get(item.getCourseId()).title() : null,
                            item.getUnitPrice()))
                    .toList();
            List<Long> owned = courseIds.stream()
                    .filter(enrollments.findOwnedCourseIds(order.getStudentId(), courseIds)::contains).toList();
            return new AdminPaymentDetailResponse(payment, items, owned, null);
        });
        return new AdminPaymentDetailResponse(withoutAmount.payment(), withoutAmount.items(),
                withoutAmount.ownedCourseIds(), receivedAmount(withoutAmount.payment().transactionRef()));
    }
    private Long receivedAmount(String reference) {
        try {
            return gateway.query(reference) instanceof PaymentLookup.Paid paid ? paid.amount() : null;
        } catch (PaymentGatewayException e) {
            return null; // the provider is down: the admin decides from the other facts or later
        }
    }
}