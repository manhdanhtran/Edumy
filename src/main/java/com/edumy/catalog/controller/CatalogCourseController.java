package com.edumy.catalog.controller;

import com.edumy.catalog.dto.request.CourseSearchRequest;
import com.edumy.catalog.dto.response.CourseDetailResponse;
import com.edumy.catalog.dto.response.CourseSearchResponse;
import com.edumy.catalog.dto.response.LessonPreviewResponse;
import com.edumy.catalog.dto.response.ReviewItemResponse;
import com.edumy.catalog.service.CourseCatalogQueryService;
import com.edumy.shared.security.AuthenticatedUser;
import com.edumy.shared.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PagedModel;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public course catalog (UC-G01–G04, UC-G11): no login needed ({@code /api/v1/catalog/**} is permitAll). A valid
 * bearer token is still read: the course page then carries the caller's viewer context (D-12). An invalid or expired
 * token is rejected by the resource server with 401 like on every other path (the SPA refreshes and retries, or drops
 * the header); without any token the call is anonymous.
 */
@RestController
@RequestMapping("/api/v1/catalog/courses")
@Tag(name = "catalog")
public class CatalogCourseController {

    private final CourseCatalogQueryService queries;

    public CatalogCourseController(CourseCatalogQueryService queries) {
        this.queries = queries;
    }

    /**
     * {@code page} (0-based), {@code size} (default 20, max 100); the {@code sort} parameter is the catalog order
     * ({@code newest} default, {@code rating}, {@code price_asc}, {@code price_desc}), not a Spring Data sort. Unknown
     * {@code price} / {@code sort} → 400 {@code VALIDATION_FAILED}.
     */
    @GetMapping
    @Operation(summary = "UC-G01 UC-G02 UC-G03 Xem, tìm kiếm (không dấu), lọc và sắp xếp khóa học công khai")
    public CourseSearchResponse search(@Valid @ParameterObject @ModelAttribute CourseSearchRequest filter,
                                       @ParameterObject Pageable pageable) {
        return queries.search(filter.q(), filter.categoryId(), filter.price(), filter.sort(), pageable);
    }

    @GetMapping("/{slug}")
    @Operation(summary = "UC-G04 Xem chi tiết khóa học (đề cương, giảng viên, điểm đánh giá, ngữ cảnh người xem)")
    public CourseDetailResponse detail(@PathVariable String slug) {
        return queries.detail(slug, currentUserId());
    }

    /** {@code page} (0-based), {@code size} (default 20, max 100); newest first ({@code sort} is ignored). */
    @GetMapping("/{slug}/reviews")
    @Operation(summary = "UC-G04 Xem các đánh giá đang hiển thị của khóa học (mới nhất trước)")
    public PagedModel<ReviewItemResponse> reviews(@PathVariable String slug, @ParameterObject Pageable pageable) {
        return new PagedModel<>(queries.reviews(slug, currentUserId(), pageable));
    }

    @GetMapping("/{slug}/lessons/{lessonId}/preview")
    @Operation(summary = "UC-G11 Xem trước bài học (không cần đăng nhập, không tạo ghi danh hay tiến độ)")
    public LessonPreviewResponse preview(@PathVariable String slug, @PathVariable Long lessonId) {
        return queries.preview(slug, lessonId);
    }

    /** The caller's id when a valid token was sent, {@code null} for an anonymous call. */
    private static Long currentUserId() {
        return AuthenticatedUser.current().map(CurrentUser::id).orElse(null);
    }
}
