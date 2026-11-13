package com.edumy.catalog.service;

import java.util.List;
import java.util.Locale;

import com.edumy.catalog.dto.response.CourseCardResponse;
import com.edumy.catalog.dto.response.CourseDetailResponse;
import com.edumy.catalog.dto.response.CourseDetailResponse.ViewerContext;
import com.edumy.catalog.dto.response.CourseSearchResponse;
import com.edumy.catalog.dto.response.LessonPreviewResponse;
import com.edumy.catalog.dto.response.ReviewItemResponse;
import com.edumy.catalog.repository.CourseCatalogRepository;
import com.edumy.catalog.repository.CourseCatalogRepository.CourseCriteria;
import com.edumy.catalog.repository.CourseCatalogRepository.CourseRef;
import com.edumy.catalog.repository.CourseCatalogRepository.CourseSort;
import com.edumy.catalog.repository.CourseCatalogRepository.PriceFilter;
import com.edumy.catalog.repository.CourseCatalogRepository.ViewerRelation;
import com.edumy.shared.error.BusinessException;
import com.edumy.shared.text.VietnameseNormalizer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PagedModel;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Public course reads (UC-G01–G04, UC-G11) over the catalog read model (ADR-003). Only {@code published} courses are
 * listed (D-08). A course page (and its reviews) is also open when the course is {@code hidden} and the caller is
 * enrolled, a course instructor or the admin (UC-G04 precondition, D-08, BR-31); anyone else gets the same 404 as for
 * an unknown slug (UC-G04 E1, NFR-01).
 * <p>Cache-friendly (docs/ai/05 §5.3, P08): the slug part of a page ({@link CourseCatalogRepository#findDetail}) does
 * not depend on the caller; the viewer context is one extra statement added on top with
 * {@link CourseDetailResponse#withViewer}.
 */
@Service
@Transactional(readOnly = true)
public class CourseCatalogQueryService {

    /** UC-G02: number of newest courses suggested when a keyword search finds nothing. */
    static final int SUGGESTION_LIMIT = 5;

    private final CourseCatalogRepository courses;

    public CourseCatalogQueryService(CourseCatalogRepository courses) {
        this.courses = courses;
    }

    /**
     * UC-G01–G03: published courses, 20 per page by default. {@code q} is matched as a part of the title, case- and
     * accent-insensitively (D-09: normalized like {@code courses.title_normalized}); filters and sort combine with it.
     * A keyword search without any result carries the newest courses as {@code suggestions} (UC-G02).
     *
     * @param q         trimmed keyword, {@code null} = none
     * @param priceCode {@code free} / {@code paid}, {@code null} = both (validated by the request record)
     * @param sortCode  {@code newest} / {@code rating} / {@code price_asc} / {@code price_desc}, {@code null} = newest
     */
    public CourseSearchResponse search(String q, Long categoryId, String priceCode, String sortCode,
                                       Pageable pageable) {
        String keyword = q == null ? null : VietnameseNormalizer.normalize(q);
        if (keyword != null && keyword.isEmpty()) {
            keyword = null;
        }
        PriceFilter price = priceCode == null ? null : PriceFilter.valueOf(priceCode.toUpperCase(Locale.ROOT));
        CourseSort sort = sortCode == null ? CourseSort.NEWEST : CourseSort.valueOf(sortCode.toUpperCase(Locale.ROOT));

        Page<CourseCardResponse> page = courses.search(new CourseCriteria(keyword, categoryId, price), sort,
                PageRequest.of(pageable.getPageNumber(), pageable.getPageSize()));
        List<CourseCardResponse> suggestions = keyword != null && page.getTotalElements() == 0
                ? courses.newest(SUGGESTION_LIMIT)
                : List.of();
        return new CourseSearchResponse(page.getContent(), new PagedModel<>(page).getMetadata(), suggestions);
    }

    /**
     * UC-G04: the course page by slug with the caller's context (D-12); {@code viewerId} {@code null} = anonymous
     * ({@code viewer = null}). Unknown slug, draft / pending_review / rejected course, or hidden course the caller may
     * not open → 404 {@code RESOURCE_NOT_FOUND}.
     */
    public CourseDetailResponse detail(String slug, Long viewerId) {
        CourseDetailResponse page = courses.findDetail(slug).orElseThrow(BusinessException::notFound);
        boolean hidden = "hidden".equals(page.status());
        if (viewerId == null) {
            if (hidden) {
                throw BusinessException.notFound();
            }
            return page;
        }
        ViewerRelation relation = courses.relation(page.id(), viewerId);
        if (hidden && !mayOpenHidden(relation)) {
            throw BusinessException.notFound();
        }
        return page.withViewer(new ViewerContext(relation.owned(), relation.inCart(), relation.instructor()));
    }

    /** UC-G04 step 3: visible reviews of the course page {@code slug}, newest first (same visibility as the page). */
    public Page<ReviewItemResponse> reviews(String slug, Long viewerId, Pageable pageable) {
        CourseRef course = courses.findCourseRef(slug).orElseThrow(BusinessException::notFound);
        if (course.hidden() && (viewerId == null || !mayOpenHidden(courses.relation(course.id(), viewerId)))) {
            throw BusinessException.notFound();
        }
        return courses.visibleReviews(course.id(), PageRequest.of(pageable.getPageNumber(), pageable.getPageSize()));
    }

    /**
     * UC-G11: a lesson marked as preview of a published course, readable without login (no enrollment, no progress).
     * Not a preview lesson, lesson of another course, or course not published → 404 (identical body).
     */
    public LessonPreviewResponse preview(String slug, Long lessonId) {
        return courses.findPreview(slug, lessonId).orElseThrow(BusinessException::notFound);
    }

    /** UC-G04 precondition / D-08 / BR-31: who may still open a hidden course page. */
    private static boolean mayOpenHidden(ViewerRelation relation) {
        return relation.owned() || relation.instructor() || relation.admin();
    }
}
