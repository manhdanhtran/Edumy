package com.edumy.commerce.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import com.edumy.commerce.api.PaymentReconciled;
import com.edumy.commerce.dto.response.AdminPaymentResponse;
import com.edumy.commerce.entity.HandledAction;
import com.edumy.commerce.entity.Order;
import com.edumy.commerce.entity.PaymentTransaction;
import com.edumy.commerce.repository.OrderRepository;
import com.edumy.commerce.repository.PaymentTransactionRepository;
import com.edumy.identity.api.RoleQueryApi;
import com.edumy.learning.api.EnrollmentApi;
import com.edumy.shared.error.BusinessException;
import com.edumy.shared.error.ErrorCode;
import com.edumy.shared.jpa.CodeEnum;
import com.edumy.shared.security.RoleCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * UC-A18 reconciliation of a {@code needs_review} attempt (P11-d, D-29, BR-26, BR-30; ADR-011 lock order).
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final OrderRepository orders;
    private final PaymentTransactionRepository payments;
    private final RoleQueryApi roles;
    private final EnrollmentApi enrollments;
    private final PaymentResultApplier applier;
    private final PaymentQueryService queries;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;
    private final Clock clock;

    public ReconciliationService(OrderRepository orders, PaymentTransactionRepository payments, RoleQueryApi roles,
                                 EnrollmentApi enrollments, PaymentResultApplier applier, PaymentQueryService queries,
                                 ApplicationEventPublisher events, PlatformTransactionManager transactionManager,
                                 Clock clock) {
        this.orders = orders;
        this.payments = payments;
        this.roles = roles;
        this.enrollments = enrollments;
        this.applier = applier;
        this.queries = queries;
        this.events = events;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * UC-A18. The admin role is re-checked in the database (BR-26 → 403 {@code ACCESS_DENIED}). The order id is read as
     * a scalar, then the order and the attempt are locked in that order (R1, ADR-011), so two admins (or a late
     * provider result) never handle one attempt twice: the second sees it handled → 409 {@code DATA_CONFLICT} (E1).
     * Unknown id → 404; not an unhandled {@code needs_review} → 409 (E1).
     * <ul>
     *   <li>(a) {@code activated}: every course of the order already owned → 409 {@code DATA_CONFLICT} (E2); otherwise the
     *   attempt becomes {@code success} (confirmed now), the order {@code paid} (from pending or cancelled; already paid
     *   by another attempt stays paid), purchase enrollments for the courses not owned, those courses leave the
     *   cart — one transaction (step 4, NFR-04).</li>
     *   <li>(b) {@code refund_outside}: the attempt keeps its result ({@code needs_review}), no access (step 4).</li>
     * </ul>
     * Both store who, when, which action and the trimmed note (step 5; blank note → 400 on {@code note}, E3) and publish
     * {@link PaymentReconciled} (mail to the student after commit, step 6). Returns the attempt as the admin list shows
     * it.
     */
    public AdminPaymentResponse resolve(ResolvePaymentCommand command) {
        if (!roles.hasActiveRole(command.adminId(), RoleCode.ADMIN)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED); // BR-26: admin role re-checked at action time
        }
        HandledAction action = CodeEnum.fromCode(HandledAction.class, command.action());
        tx.executeWithoutResult(status -> resolveLocked(command, action));
        return queries.getAdmin(command.paymentId());
    }

    private void resolveLocked(ResolvePaymentCommand command, HandledAction action) {
        Long orderId = payments.findOrderIdById(command.paymentId()).orElseThrow(BusinessException::notFound);
        Order order = orders.findByIdForUpdate(orderId).orElseThrow(BusinessException::notFound);
        PaymentTransaction payment = payments.findByIdForUpdate(command.paymentId())
                .orElseThrow(BusinessException::notFound);
        if (!payment.isAwaitingReview()) {
            throw new BusinessException(ErrorCode.DATA_CONFLICT); // E1: already handled, or not needs_review
        }
        Instant now = clock.instant();
        List<Long> granted = List.of();
        if (action == HandledAction.ACTIVATED) {
            List<Long> courseIds = order.courseIds();
            if (enrollments.findOwnedCourseIds(order.getStudentId(), courseIds).containsAll(courseIds)) {
                throw new BusinessException(ErrorCode.DATA_CONFLICT); // E2: nothing left to grant
            }
            payment.activate(command.adminId(), command.note(), now);
            if (!order.isPaid()) {
                order.markPaid(now);
            }
            Set<Long> enrolled = applier.fulfil(order, now);
            granted = courseIds.stream().filter(enrolled::contains).toList();
        } else {
            payment.refundOutside(command.adminId(), command.note(), now);
        }
        events.publishEvent(new PaymentReconciled(payment.getId(), order.getOrderCode(), order.getStudentId(),
                action.code(), granted));
        log.info("Payment reconciled: transactionRef={}, orderCode={}, action={}, adminId={}, grantedCourseIds={}",
                payment.getTransactionRef(), order.getOrderCode(), action.code(), command.adminId(), granted);
    }
}
