package com.edumy.authoring.controller;

import com.edumy.authoring.dto.response.PendingCourseResponse;
import com.edumy.authoring.service.CourseQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PagedModel;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Course review by the admin (UC-A09…A12, UC-A16). Admin only: route rule {@code /api/v1/admin/**} plus the
 * method-level check (docs/ai/04 §4.3). The content of a pending course (UC-A10 step 1) is read through
 * {@code GET /api/v1/learn/courses/{courseId}} and {@code GET /api/v1/learn/lessons/{lessonId}} (admin without
 * enrollment, D-32).
 */
@RestController
@RequestMapping("/api/v1/admin/courses")
@Tag(name = "authoring")
@PreAuthorize("hasRole('ADMIN')")
public class AdminCourseReviewController {

    private final CourseQueryService queries;

    public AdminCourseReviewController(CourseQueryService queries) {
        this.queries = queries;
    }

    /** {@code page} (0-based), {@code size} (default 20, max 100); oldest submission first ({@code sort} ignored). */
    @GetMapping("/pending")
    @Operation(summary = "UC-A09 Xem danh sách khóa học chờ duyệt (gửi sớm nhất đứng trước)")
    public PagedModel<PendingCourseResponse> pending(@ParameterObject Pageable pageable) {
        return new PagedModel<>(queries.pendingQueue(pageable));
    }
}
