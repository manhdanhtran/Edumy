package com.edumy.commerce.service;

import com.edumy.authoring.api.CourseQueryApi;
import com.edumy.authoring.api.CourseSummary;
import com.edumy.commerce.dto.response.CartResponse;
import com.edumy.commerce.entity.CartItem;
import com.edumy.commerce.mapper.CartMapper;
import com.edumy.commerce.repository.CartRepository;
import com.edumy.identity.api.UserQueryApi;
import com.edumy.identity.api.UserSummary;
import com.edumy.learning.api.EnrollmentApi;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * UC-S02 read of the caller's cart. Never writes (a student without a cart gets an empty one, BR-22). Cart + items in
 * one statement, then one batch call each to authoring (courses), learning (owned courses) and identity (instructor
 * names): 4 statements for any cart size, 1 for an empty cart.
 */
@Service
@Transactional(readOnly = true)
public class CartQueryService {

    private final CartRepository carts;
    private final CourseQueryApi courses;
    private final EnrollmentApi enrollments;
    private final UserQueryApi users;
    private final CartMapper mapper;

    public CartQueryService(CartRepository carts, CourseQueryApi courses, EnrollmentApi enrollments,
                            UserQueryApi users, CartMapper mapper) {
        this.carts = carts;
        this.courses = courses;
        this.enrollments = enrollments;
        this.users = users;
        this.mapper = mapper;
    }

    public CartResponse getCart(Long studentId) {
        List<CartItem> items = carts.findWithItemsByStudentId(studentId)
                .map(cart -> cart.getItems())
                .orElse(List.of());
        if (items.isEmpty()) {
            return mapper.toResponse(List.of());
        }
        List<Long> courseIds = items.stream().map(CartItem::getCourseId).toList();
        Map<Long, CourseSummary> summaries = courses.findSummaries(courseIds);
        Set<Long> owned = enrollments.findOwnedCourseIds(studentId, courseIds);
        Map<Long, UserSummary> instructors = users.findSummaries(
                summaries.values().stream().map(CourseSummary::mainInstructorId).toList());
        return mapper.toResponse(items.stream()
                .map(item -> {
                    CourseSummary course = summaries.get(item.getCourseId());
                    return mapper.toItem(item, course,
                            course == null ? null : instructors.get(course.mainInstructorId()),
                            owned.contains(item.getCourseId()), studentId);
                })
                .toList());
    }
}
