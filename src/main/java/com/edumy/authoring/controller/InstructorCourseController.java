package com.edumy.authoring.controller;

import com.edumy.authoring.dto.response.InstructorCourseDetailResponse;
import com.edumy.authoring.dto.response.InstructorCourseSummaryResponse;
import com.edumy.authoring.dto.response.ReviewStatusResponse;
import com.edumy.authoring.service.CourseQueryService;
import com.edumy.shared.security.CurrentUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PagedModel;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Courses of the calling instructor (UC-I01…I04, UC-I17/I18). Every operation needs an <b>active</b> instructor role
 * in the database (BR-26, D-07: a revoked instructor gets 403 whatever the JWT says); a course the caller is not an
 * instructor of answers 404 like an unknown id (NFR-01, docs/ai/04 §4.3).
 */
@RestController
@RequestMapping("/api/v1/instructor/courses")
@Tag(name = "authoring")
public class InstructorCourseController {

    private final CourseQueryService queries;

    public InstructorCourseController(CourseQueryService queries) {
        this.queries = queries;
    }

    /** {@code page} (0-based), {@code size} (default 20, max 100); newest course first ({@code sort} is ignored). */
    @GetMapping
    @PreAuthorize("@courseAccess.isActiveInstructor()")
    @Operation(summary = "UC-I02 Xem danh sách khóa học phụ trách (GV chính hoặc đồng GV đã nhận lời)")
    public PagedModel<InstructorCourseSummaryResponse> listMine(@CurrentUserId Long userId,
                                                                @ParameterObject Pageable pageable) {
        return new PagedModel<>(queries.listMine(userId, pageable));
    }

    /** Readable in every status, {@code pending_review} included (BR-33 forbids editing only). */
    @GetMapping("/{id}")
    @PreAuthorize("@courseAccess.isInstructor(#id)")
    @Operation(summary = "UC-I02 UC-I03 Xem chi tiết khóa học phụ trách (thông tin, chương, bài và nội dung, đồng GV)")
    public InstructorCourseDetailResponse detail(@PathVariable Long id, @CurrentUserId Long userId) {
        return queries.detail(id, userId);
    }

    @GetMapping("/{id}/review-status")
    @PreAuthorize("@courseAccess.isInstructor(#id)")
    @Operation(summary = "UC-I18 Xem trạng thái duyệt khóa học (thời điểm gửi, xử lý, lý do từ chối/ẩn gần nhất)")
    public ReviewStatusResponse reviewStatus(@PathVariable Long id) {
        return queries.reviewStatus(id);
    }
}
