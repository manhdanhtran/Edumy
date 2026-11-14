package com.edumy.commerce.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.edumy.commerce.dto.request.ResolvePaymentRequest;
import com.edumy.commerce.dto.response.AdminPaymentResponse;
import com.edumy.commerce.entity.Order;
import com.edumy.commerce.entity.PaymentTransaction;
import com.edumy.commerce.mapper.PaymentMapperImpl;
import com.edumy.commerce.repository.PaymentTransactionRepository;
import com.edumy.identity.api.RoleQueryApi;
import com.edumy.identity.api.UserQueryApi;
import com.edumy.identity.api.UserSummary;
import com.edumy.learning.api.EnrollmentApi;
import com.edumy.shared.error.BusinessException;
import com.edumy.shared.error.ErrorCode;
import com.edumy.shared.security.RoleCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** UC-A18 stub rules that the dev seed cannot show: E2 (every course already owned) and the DB admin re-check. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReconciliationServiceTest {

    static final Instant NOW = Instant.parse("2026-11-15T10:00:00Z");
    static final long ADMIN = 1L;
    static final long STUDENT = 12L;

    @Mock
    PaymentTransactionRepository payments;
    @Mock
    RoleQueryApi roles;
    @Mock
    UserQueryApi users;
    @Mock
    EnrollmentApi enrollments;

    ReconciliationService service;
    PaymentTransaction payment;

    @BeforeEach
    void setUp() {
        service = new ReconciliationService(payments, roles, users, enrollments, new PaymentMapperImpl(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        Instant created = NOW.minusSeconds(3600);
        Order order = Order.create(STUDENT, "ORD-20261114-0001",
                List.of(new Order.Line(9L, 450_000), new Order.Line(5L, 699_000)), created);
        payment = PaymentTransaction.create(order, "FAKEPAY-X", order.getTotalAmount(), "FAKEQR|X",
                created.plusSeconds(900), created);
        payment.flagNeedsReview(created.plusSeconds(1000));
        when(roles.hasActiveRole(ADMIN, RoleCode.ADMIN)).thenReturn(true);
    }

    @Test
    void activated_whenEveryCourseIsAlreadyOwned_409_E2() {
        when(payments.findById(5L)).thenReturn(Optional.of(payment));
        when(enrollments.findOwnedCourseIds(STUDENT, List.of(9L, 5L))).thenReturn(Set.of(9L, 5L));

        assertThatThrownBy(() -> service.resolve(ADMIN, 5L, new ResolvePaymentRequest("activated", "ok")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.DATA_CONFLICT));
    }

    @Test
    void refundOutside_isAllowedEvenWhenEveryCourseIsOwned() {
        when(payments.findById(5L)).thenReturn(Optional.of(payment));
        when(users.findSummaries(List.of(STUDENT, ADMIN))).thenReturn(Map.of(
                STUDENT, new UserSummary(STUDENT, "Học viên", "hv@edumy.vn", "active"),
                ADMIN, new UserSummary(ADMIN, "Quản trị", "admin@edumy.vn", "active")));

        AdminPaymentResponse response = service.resolve(ADMIN, 5L, new ResolvePaymentRequest("refund_outside", " ok "));

        assertThat(response.status()).isEqualTo("needs_review");
        assertThat(response.handledAction()).isEqualTo("refund_outside");
        assertThat(response.handledNote()).isEqualTo("ok");
        assertThat(response.handledAt()).isEqualTo(NOW);
        assertThat(response.handledBy().fullName()).isEqualTo("Quản trị");
        // the entity itself was not changed (stub: nothing is written)
        assertThat(payment.isAwaitingReview()).isTrue();
        verifyNoInteractions(enrollments);
    }

    @Test
    void activated_whenOneCourseIsNotOwned_returnsSuccess() {
        when(payments.findById(5L)).thenReturn(Optional.of(payment));
        when(enrollments.findOwnedCourseIds(STUDENT, List.of(9L, 5L))).thenReturn(Set.of(9L));
        when(users.findSummaries(List.of(STUDENT, ADMIN))).thenReturn(Map.of());

        AdminPaymentResponse response = service.resolve(ADMIN, 5L, new ResolvePaymentRequest("activated", "ok"));

        assertThat(response.status()).isEqualTo("success");
        assertThat(response.confirmedAt()).isEqualTo(NOW);
        assertThat(payment.isAwaitingReview()).isTrue();
    }

    @Test
    void staleAdmin_403_beforeAnyLookup() {
        when(roles.hasActiveRole(2L, RoleCode.ADMIN)).thenReturn(false);

        assertThatThrownBy(() -> service.resolve(2L, 5L, new ResolvePaymentRequest("activated", "ok")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.ACCESS_DENIED));
        verifyNoMoreInteractions(payments);
    }
}
