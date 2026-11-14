package com.edumy.catalog.service;

import java.util.List;

import com.edumy.catalog.dto.response.CourseCardResponse;
import com.edumy.catalog.dto.response.CourseDetailResponse;
import com.edumy.catalog.dto.response.CourseSearchResponse;
import com.edumy.catalog.repository.CourseCatalogRepository;
import com.edumy.catalog.repository.CourseCatalogRepository.CourseCriteria;
import com.edumy.catalog.repository.CourseCatalogRepository.CourseSort;
import com.edumy.catalog.repository.CourseCatalogRepository.PriceFilter;
import com.edumy.catalog.repository.CourseCatalogRepository.RatingSummary;
import com.edumy.shared.config.CacheConfig;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.web.PagedModel;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The cached, viewer-independent part of the public course reads (docs/ai/05 §5.3, P08). Kept in its own bean because
 * Spring's cache proxy does not apply to a self-invocation: {@link CourseCatalogQueryService} calls these methods from
 * outside. Only immutable records are cached, never an {@code Optional}, never a "not found" (a slug published later
 * must show at once), and never anything that depends on the caller (the viewer context is added by the caller of
 * {@link #publishedDetail}).
 */
@Service
@Transactional(readOnly = true)
public class CachedCourseQueryService {

    /** UC-G02: number of newest courses suggested when a keyword search finds nothing. */
    static final int SUGGESTION_LIMIT = 5;

    /**
     * Cache key of one list page: the <b>normalized</b> keyword ({@code VietnameseNormalizer}, {@code null} = none, so
     * {@code lap trinh} and {@code lập trình} share an entry), the filters, the sort and the page. A record has
     * value-based {@code equals}/{@code hashCode}: two different requests can never collide.
     */
    public record SearchKey(String keyword, Long categoryId, PriceFilter price, CourseSort sort, int page, int size) {
    }

    private final CourseCatalogRepository courses;

    public CachedCourseQueryService(CourseCatalogRepository courses) {
        this.courses = courses;
    }

    /**
     * UC-G01–G03 for {@code key}: the page of published course cards and, for a keyword search without any result,
     * the newest courses as {@code suggestions} (UC-G02). 60 s TTL, no eviction (accepted staleness, §5.3).
     */
    @Cacheable(cacheNames = CacheConfig.CATALOG_COURSE_LIST, key = "#key")
    public CourseSearchResponse search(SearchKey key) {
        Page<CourseCardResponse> page = courses.search(new CourseCriteria(key.keyword(), key.categoryId(), key.price()),
                key.sort(), PageRequest.of(key.page(), key.size()));
        List<CourseCardResponse> suggestions = key.keyword() != null && page.getTotalElements() == 0
                ? courses.newest(SUGGESTION_LIMIT)
                : List.of();
        return new CourseSearchResponse(page.getContent(), new PagedModel<>(page).getMetadata(), suggestions);
    }

    /**
     * UC-G04: the page of {@code slug} (rating merged from the {@code catalog.rating} cache, no viewer context), or
     * {@code null} for an unknown slug / a course that is neither published nor hidden. Cached 10 min by slug only
     * when the course is {@code published}: a {@code null} (unknown slug) or a {@code hidden} page is never stored.
     */
    @Cacheable(cacheNames = CacheConfig.CATALOG_COURSE_DETAIL, key = "#slug",
            unless = "#result == null || #result.status() != 'published'")
    public CourseDetailResponse publishedDetail(String slug) {
        return courses.findDetail(slug).map(page -> {
            RatingSummary rating = courses.findRating(page.id());
            return page.withRating(rating.average(), rating.count());
        }).orElse(null);
    }
}
