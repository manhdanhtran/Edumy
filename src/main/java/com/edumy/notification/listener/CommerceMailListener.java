package com.edumy.notification.listener;

import com.edumy.commerce.api.OrderPaid;
import com.edumy.commerce.api.PaymentReconciled;
import com.edumy.notification.service.MailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Mails of the commerce flows (SRS §7 "Xác nhận mua hàng", "Kết quả đối soát"), sent asynchronously after the payment
 * transaction committed ({@code @Async} + {@code @TransactionalEventListener} AFTER_COMMIT, like the other listeners).
 * BR-20 / UC-S05 E4 / AC-03: a failure is caught and logged here and never touches the order or the enrollments,
 * already committed; the log carries the event type, the orderCode and exception class names only (NFR-08).
 */
@Component
public class CommerceMailListener {

    private static final Logger log = LoggerFactory.getLogger(CommerceMailListener.class);

    private final MailService mails;

    public CommerceMailListener(MailService mails) {
        this.mails = mails;
    }

    /** UC-S05 step 9: purchase confirmation. */
    @Async
    @TransactionalEventListener
    public void on(OrderPaid event) {
        try {
            mails.sendPurchaseConfirmed(event.studentId(), event.orderCode(), event.totalAmount(), event.courseIds());
        } catch (RuntimeException e) {
            failed("OrderPaid", event.orderCode(), e);
        }
    }

    /** UC-A18 step 6: reconciliation result. */
    @Async
    @TransactionalEventListener
    public void on(PaymentReconciled event) {
        try {
            mails.sendReconciliationResult(event.studentId(), event.orderCode(), event.activated(),
                    event.grantedCourseIds());
        } catch (RuntimeException e) {
            failed("PaymentReconciled", event.orderCode(), e);
        }
    }

    private static void failed(String event, String orderCode, RuntimeException e) {
        Throwable cause = NestedExceptionUtils.getMostSpecificCause(e);
        log.error("Mail not sent: event={}, orderCode={}, error={}, cause={}", event, orderCode,
                e.getClass().getName(), cause.getClass().getName());
    }
}
