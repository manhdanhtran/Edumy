package com.edumy.commerce.service;

import java.time.Clock;
import java.util.List;
import java.util.Map;

import com.edumy.commerce.dto.request.ResolvePaymentRequest;
import com.edumy.commerce.dto.response.AdminPaymentResponse;
import com.edumy.commerce.entity.HandledAction;
import com.edumy.commerce.entity.Order;
import com.edumy.commerce.entity.PaymentTransaction;
import com.edumy.commerce.mapper.PaymentMapper;
import com.edumy.commerce.repository.PaymentTransactionRepository;
import com.edumy.identity.api.RoleQueryApi;
import com.edumy.identity.api.UserQueryApi;
import com.edumy.identity.api.UserSummary;
import com.edumy.learning.api.EnrollmentApi;
import com.edumy.shared.error.BusinessException;
import com.edumy.shared.error.ErrorCode;
import com.edumy.shared.jpa.CodeEnum;
import com.edumy.shared.security.RoleCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * UC-A18 reconciliation, <b>phase-1 STUB</b> (ADR-005): reads and computes, never writes. Phase 2 replaces the body with
 * the real transaction (row lock, {@code PaymentTransaction.activate / refundOutside}, order paid + enrollments in one
 * transaction, mail to the student); the controller stays.
 */
@Service
public class ReconciliationService {

    private final PaymentTransactionRepository payments;
    private final RoleQueryApi roles;
    private final UserQueryApi users;
    private final EnrollmentApi enrollments;
    private final PaymentMapper mapper;
    private final Clock clock;

    public ReconciliationService(PaymentTransactionRepository payments, RoleQueryApi roles, UserQueryApi users,
                                 EnrollmentApi enrollments, PaymentMapper mapper, Clock clock) {
        this.payments = payments;
        this.roles = roles;
        this.users = users;
        this.enrollments = enrollments;
        this.mapper = mapper;
        this.clock = clock;
    }

    /**
     * UC-A18 preview. The admin role is re-checked in the database (BR-26 → 403 {@code ACCESS_DENIED}); unknown payment
     * → 404; the attempt must be {@code needs_review} and not handled yet (E1 → 409 {@code DATA_CONFLICT}, the code
     * {@code PaymentTransaction.handle} throws); {@code activated} is refused (409 {@code DATA_CONFLICT}) when the student
     * already owns every course of the order (E2). Returns the attempt as it would look after handling.
     */
    @Transactional(readOnly = true)
    public AdminPaymentResponse resolve(Long adminId, Long paymentId, ResolvePaymentRequest request) {
        if (!roles.hasActiveRole(adminId, RoleCode.ADMIN)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED); // BR-26: admin role re-checked at action time
        }
        PaymentTransaction payment = payments.findById(paymentId).orElseThrow(BusinessException::notFound);
        if (!payment.isAwaitingReview()) {
            throw new BusinessException(ErrorCode.DATA_CONFLICT);
        }
        Order order = payment.getOrder();
        HandledAction action = CodeEnum.fromCode(HandledAction.class, request.action());
        if (action == HandledAction.ACTIVATED) {
            List<Long> courseIds = order.courseIds();
            if (enrollments.findOwnedCourseIds(order.getStudentId(), courseIds).containsAll(courseIds)) {
                throw new BusinessException(ErrorCode.DATA_CONFLICT);
            }
        }
        Map<Long, UserSummary> names = users.findSummaries(List.of(order.getStudentId(), adminId));
        return mapper.toResolvedAdmin(payment, order, action, request.note().strip(), adminId, clock.instant(), names);
    }
}
