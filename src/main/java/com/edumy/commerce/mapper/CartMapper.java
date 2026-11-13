package com.edumy.commerce.mapper;

import com.edumy.authoring.api.CourseSummary;
import com.edumy.commerce.dto.response.CartResponse;
import com.edumy.commerce.entity.CartItem;
import com.edumy.identity.api.UserSummary;
import java.util.List;
import org.mapstruct.Mapper;

/**
 * Cart lines → {@link CartResponse}. Course facts come from {@code authoring::api}, the instructor name from
 * {@code identity::api}, ownership from {@code learning::api}; the service fetches all of them for the whole cart at
 * once. {@code isFree} is derived from the price (docs/ai/05 §5.1).
 */
@Mapper(componentModel = "spring")
public interface CartMapper {

    String REASON_COURSE_UNAVAILABLE = "course_unavailable";
    String REASON_ALREADY_OWNED = "already_owned";
    String REASON_OWN_COURSE = "own_course";
    String REASON_NOW_FREE = "now_free";

    /**
     * One line. Reason precedence: a course that is not sold any more (BR-31) → already owned (BR-10) → own course
     * (BR-32) → price became 0 (BR-09). A course missing from {@code course} (cannot happen: FK) counts as unavailable.
     */
    default CartResponse.Item toItem(CartItem item, CourseSummary course, UserSummary instructor, boolean owned,
                                     Long studentId) {
        if (course == null) {
            return new CartResponse.Item(item.getCourseId(), null, null, null, 0, true, null, item.getAddedAt(), false,
                    REASON_COURSE_UNAVAILABLE);
        }
        String reason = null;
        if (!course.published()) {
            reason = REASON_COURSE_UNAVAILABLE;
        } else if (owned) {
            reason = REASON_ALREADY_OWNED;
        } else if (course.mainInstructorId().equals(studentId)) {
            reason = REASON_OWN_COURSE;
        } else if (course.price() == 0) {
            reason = REASON_NOW_FREE;
        }
        return new CartResponse.Item(course.id(), course.title(), course.slug(), course.thumbnailUrl(), course.price(),
                course.price() == 0,
                new CartResponse.Instructor(course.mainInstructorId(), instructor == null ? null : instructor.fullName()),
                item.getAddedAt(), reason == null, reason);
    }

    /** Totals over the purchasable lines only (D-26). */
    default CartResponse toResponse(List<CartResponse.Item> items) {
        List<CartResponse.Item> purchasable = items.stream().filter(CartResponse.Item::purchasable).toList();
        return new CartResponse(items, items.size(), purchasable.size(),
                purchasable.stream().mapToLong(CartResponse.Item::price).sum());
    }
}
