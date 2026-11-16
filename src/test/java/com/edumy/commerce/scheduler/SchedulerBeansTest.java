package com.edumy.commerce.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Method;

import com.edumy.commerce.service.CommerceExpiryService;
import com.edumy.identity.scheduler.TokenCleanupScheduler;
import com.edumy.identity.service.TokenCleanupService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * NFR-09 / P11-e: the three timers exist only when {@code edumy.scheduler.enabled=true} (production / dev default; the
 * test profile turns them off), the commerce ones every 5 minutes, the code cleanup once a day (ADR-012), and only
 * delegate to their service.
 */
class SchedulerBeansTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(CommerceExpiryService.class, () -> mock(CommerceExpiryService.class))
            .withBean(TokenCleanupService.class, () -> mock(TokenCleanupService.class))
            .withUserConfiguration(PaymentExpiryScheduler.class, PendingOrderExpiryScheduler.class,
                    TokenCleanupScheduler.class);

    @Test
    void enabled_theThreeTimersExist() {
        runner.withPropertyValues("edumy.scheduler.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(PaymentExpiryScheduler.class);
            assertThat(context).hasSingleBean(PendingOrderExpiryScheduler.class);
            assertThat(context).hasSingleBean(TokenCleanupScheduler.class);
        });
    }

    @Test
    void disabledOrUnset_noTimer() {
        runner.withPropertyValues("edumy.scheduler.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(PaymentExpiryScheduler.class);
            assertThat(context).doesNotHaveBean(PendingOrderExpiryScheduler.class);
            assertThat(context).doesNotHaveBean(TokenCleanupScheduler.class);
        });
        runner.run(context -> assertThat(context).doesNotHaveBean(PaymentExpiryScheduler.class));
    }

    @Test
    void commerceTimersEveryFiveMinutes_codeCleanupOnceADayAt3_ADR012() throws Exception {
        Scheduled cleanup = TokenCleanupScheduler.class.getMethod("run").getAnnotation(Scheduled.class);
        assertThat(cleanup.cron()).isEqualTo("${edumy.scheduler.token-cleanup-cron:0 0 3 * * *}");
        assertThat(cleanup.zone()).isEqualTo("${edumy.scheduler.zone:Asia/Ho_Chi_Minh}");
        assertThat(cleanup.fixedDelayString()).isEmpty();
        for (Class<?> type : new Class<?>[] {PaymentExpiryScheduler.class, PendingOrderExpiryScheduler.class}) {
            Method run = type.getMethod("run");
            assertThat(run.getAnnotation(Scheduled.class).fixedDelayString()).as(type.getSimpleName())
                    .isEqualTo("${edumy.scheduler.interval:PT5M}");
        }
    }

    @Test
    void timersOnlyDelegate() {
        CommerceExpiryService expiry = mock(CommerceExpiryService.class);
        TokenCleanupService cleanup = mock(TokenCleanupService.class);

        new PaymentExpiryScheduler(expiry).run();
        new PendingOrderExpiryScheduler(expiry).run();
        new TokenCleanupScheduler(cleanup).run();

        verify(expiry).expireQrAttempts();
        verify(expiry).cancelOverdueOrders();
        verify(cleanup).deleteExpiredCodes();
    }
}
