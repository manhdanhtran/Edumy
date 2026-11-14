package com.edumy.commerce.service;

import java.time.Clock;
import java.util.List;

import com.edumy.authoring.api.CourseAccessApi;
import com.edumy.authoring.api.CourseQueryApi;
import com.edumy.authoring.api.CourseSummary;
import com.edumy.commerce.entity.Cart;
import com.edumy.commerce.repository.CartRepository;
import com.edumy.learning.api.EnrollmentApi;
import com.edumy.shared.error.BusinessException;
import com.edumy.shared.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cart writes: UC-S01 add a course, UC-S03 remove one. The cart is always the caller's own (scoped by user id), so
 * another student's cart or item is unreachable (NFR-01). The cart is never cached.
 */
@Service
public class CartService {

    private static final Logger log = LoggerFactory.getLogger(CartService.class);

    private final CartRepository carts;
    private final CourseQueryApi courses;
    private final CourseAccessApi courseAccess;
    private final EnrollmentApi enrollments;
    private final Clock clock;

    public CartService(CartRepository carts, CourseQueryApi courses, CourseAccessApi courseAccess,
                       EnrollmentApi enrollments, Clock clock) {
        this.carts = carts;
        this.courses = courses;
        this.courseAccess = courseAccess;
        this.enrollments = enrollments;
        this.clock = clock;
    }

    /**
     * UC-S01. Checks in SRS order (main flow step 1), then creates the cart if needed (BR-22) and adds the item
     * (BR-11). Concurrent adds of one student are serialised by the row lock of the cart; a race that still reaches
     * {@code uq_cart_items_cart_course} is translated to {@code CART_ITEM_DUPLICATE}.
     */
    @Transactional
    public void addItem(Long studentId, Long courseId) {
        CourseSummary course = courses.findSummaries(List.of(courseId)).get(courseId);
        // BR-31: only published courses are sold; unknown / draft / hidden look the same (404, no probing)
        if (course == null || !course.published()) {
            throw BusinessException.notFound();
        }
        // BR-09 / UC-S01 E3: free courses are enrolled directly
        if (course.price() == 0) {
            throw new BusinessException(ErrorCode.CART_COURSE_FREE);
        }
        // BR-10 / E2
        if (enrollments.owns(studentId, courseId)) {
            throw new BusinessException(ErrorCode.CART_COURSE_ALREADY_OWNED);
        }
        // BR-32 / E4: main or accepted co-instructor; the main instructor counts whatever the role state
        if (course.mainInstructorId().equals(studentId) || courseAccess.isCourseInstructor(studentId, courseId)) {
            throw new BusinessException(ErrorCode.CART_OWN_COURSE);
        }
        // BR-22: one cart per account, created by the first add; then lock it so adds of one student queue up
        carts.createIfAbsent(studentId, clock.instant());
        Cart cart = carts.findByStudentIdForUpdate(studentId).orElseThrow();
        cart.addItem(courseId, clock.instant()); // BR-11 pre-check → CART_ITEM_DUPLICATE
        carts.flush(); // a unique violation surfaces here, inside the service
        log.info("Cart item added: userId={}, courseId={}", studentId, courseId);
    }

    /**
     * UC-S03: removes {@code courseId} from the caller's cart. Not in the caller's cart (no cart, never added, or only
     * in someone else's) → 404, identical to any other 404.
     */
    @Transactional
    public void removeItem(Long studentId, Long courseId) {
        Cart cart = carts.findByStudentIdForUpdate(studentId).orElseThrow(BusinessException::notFound);
        if (!cart.removeItem(courseId)) {
            throw BusinessException.notFound();
        }
        carts.flush();
        log.info("Cart item removed: userId={}, courseId={}", studentId, courseId);
    }
}
