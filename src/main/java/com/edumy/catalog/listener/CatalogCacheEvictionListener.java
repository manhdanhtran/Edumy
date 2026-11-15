package com.edumy.catalog.listener;

import com.edumy.authoring.api.CategoryChanged;
import com.edumy.authoring.api.CourseApproved;
import com.edumy.authoring.api.CourseContentChanged;
import com.edumy.authoring.api.CourseHidden;
import com.edumy.authoring.api.CourseUnhidden;
import com.edumy.catalog.service.CatalogCacheEvictor;
import com.edumy.learning.api.ReviewChanged;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Evicts the catalog caches after the writing transaction committed (docs/ai/05 §5.3, docs/ai/01 §1.10). Synchronous
 * on purpose (not {@code @Async}): the next request after the write's response must already see the eviction.
 * {@code CourseRejected} is not handled: a rejected course was never published, so the catalog never cached it.
 * {@code catalog.course-list} is emptied when a course enters or leaves the catalog (approve / hide / unhide, P10
 * Q-a), otherwise TTL-only. An event published outside a transaction would be dropped by
 * {@code AFTER_COMMIT}; every publisher is {@code @Transactional}.
 */
@Component
public class CatalogCacheEvictionListener {

    private final CatalogCacheEvictor evictor;

    public CatalogCacheEvictionListener(CatalogCacheEvictor evictor) {
        this.evictor = evictor;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void on(CategoryChanged event) {
        evictor.evictCategories();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void on(CourseContentChanged event) {
        evictor.evictCourseDetail(event.slug());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void on(CourseApproved event) {
        evictor.evictPublication(event.slug());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void on(CourseHidden event) {
        evictor.evictPublication(event.slug());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void on(CourseUnhidden event) {
        evictor.evictPublication(event.slug());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void on(ReviewChanged event) {
        evictor.evictRating(event.courseId(), event.slug());
    }
}
