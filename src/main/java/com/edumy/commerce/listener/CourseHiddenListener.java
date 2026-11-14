package com.edumy.commerce.listener;

import com.edumy.authoring.api.CourseHidden;
import com.edumy.commerce.service.CartQueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * UC-A12 step 4: "mục giỏ hàng chứa khóa được đánh dấu không khả dụng và bị loại khi lập đơn". Both effects are derived,
 * nothing is stored: {@code GET /cart} marks every line whose course is not {@code published}
 * ({@code course_unavailable}, BR-31) and UC-S04 re-validates the lines (D-26). So this reaction (after commit,
 * {@code @Async}, like the mail listeners) only records how many carts hold the hidden course, no user ids. Idempotent,
 * and a failure is logged here and never reaches the moderation request, already committed. UC-A16 (unhide) needs no
 * listener: the lines are purchasable again as soon as the course is {@code published}.
 */
@Component
public class CourseHiddenListener {

    private static final Logger log = LoggerFactory.getLogger(CourseHiddenListener.class);

    private final CartQueryService carts;

    public CourseHiddenListener(CartQueryService carts) {
        this.carts = carts;
    }

    @Async
    @TransactionalEventListener
    public void on(CourseHidden event) {
        try {
            long cartCount = carts.countCartsHolding(event.courseId());
            log.info("Course hidden, cart lines now flagged unavailable: courseId={}, carts={}", event.courseId(),
                    cartCount);
        } catch (RuntimeException e) {
            log.error("Cart flagging log failed: event={}, courseId={}, error={}", "CourseHidden", event.courseId(),
                    e.getClass().getName());
        }
    }
}
