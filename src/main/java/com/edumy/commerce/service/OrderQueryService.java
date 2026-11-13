package com.edumy.commerce.service;

import com.edumy.authoring.api.CourseQueryApi;
import com.edumy.authoring.api.CourseSummary;
import com.edumy.commerce.dto.request.AdminOrderSearchRequest;
import com.edumy.commerce.dto.response.AdminOrderResponse;
import com.edumy.commerce.dto.response.OrderDetailResponse;
import com.edumy.commerce.dto.response.OrderSummaryResponse;
import com.edumy.commerce.entity.Order;
import com.edumy.commerce.entity.OrderItem;
import com.edumy.commerce.entity.OrderStatus;
import com.edumy.commerce.mapper.OrderMapper;
import com.edumy.commerce.repository.OrderRepository;
import com.edumy.commerce.repository.OrderRepository.OrderSummaryView;
import com.edumy.commerce.repository.PaymentTransactionRepository;
import com.edumy.identity.api.UserQueryApi;
import com.edumy.identity.api.UserSummary;
import com.edumy.shared.error.BusinessException;
import com.edumy.shared.jpa.CodeEnum;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * UC-S06 / UC-S07 (own orders, scoped by the student id: another student's order is "not found", NFR-01) and UC-A13
 * (admin list). Statements: list = page + count; detail = order with lines + courses + payments; admin list = page +
 * count + names (+1 for an email filter). The order of the lists is fixed by the query; a client {@code sort} is
 * ignored.
 */
@Service
@Transactional(readOnly = true)
public class OrderQueryService {

    private final OrderRepository orders;
    private final PaymentTransactionRepository payments;
    private final CourseQueryApi courses;
    private final UserQueryApi users;
    private final OrderMapper mapper;

    public OrderQueryService(OrderRepository orders, PaymentTransactionRepository payments, CourseQueryApi courses,
                             UserQueryApi users, OrderMapper mapper) {
        this.orders = orders;
        this.payments = payments;
        this.courses = courses;
        this.users = users;
        this.mapper = mapper;
    }

    /** UC-S06: the caller's orders, newest first. */
    public Page<OrderSummaryResponse> listMine(Long studentId, Pageable pageable) {
        return orders.findSummariesByStudentId(studentId, unsorted(pageable)).map(mapper::toSummary);
    }

    /** UC-S07: unknown code and another student's code both give 404 {@code RESOURCE_NOT_FOUND}. */
    public OrderDetailResponse getMine(Long studentId, String orderCode) {
        Order order = orders.findWithItemsByOrderCodeAndStudentId(orderCode, studentId)
                .orElseThrow(BusinessException::notFound);
        Map<Long, CourseSummary> summaries = courses.findSummaries(
                order.getItems().stream().map(OrderItem::getCourseId).toList());
        return mapper.toDetail(order, summaries,
                payments.findByOrderIdOrderByCreatedAtDescIdDesc(order.getId()));
    }

    /** UC-A13: filters in {@link AdminOrderSearchRequest}; an email that matches no account gives an empty page. */
    public Page<AdminOrderResponse> search(AdminOrderSearchRequest filter, Pageable pageable) {
        Long studentId = null;
        if (filter.email() != null) {
            Optional<Long> found = users.findIdByEmail(filter.email());
            if (found.isEmpty()) {
                return Page.empty(unsorted(pageable));
            }
            studentId = found.get();
        }
        OrderStatus status = filter.status() == null ? null : CodeEnum.fromCode(OrderStatus.class, filter.status());
        Page<OrderSummaryView> page = orders.searchForAdmin(status, filter.orderCode(), studentId,
                filter.createdFrom(), filter.createdTo(), unsorted(pageable));
        Map<Long, UserSummary> students = users.findSummaries(
                page.map(OrderSummaryView::getStudentId).getContent());
        return page.map(order -> mapper.toAdmin(order, students.get(order.getStudentId())));
    }

    private static Pageable unsorted(Pageable pageable) {
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
    }
}
