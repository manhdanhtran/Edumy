package com.edumy.authoring.controller;

import com.edumy.authoring.dto.request.HideCourseRequest;
import com.edumy.authoring.dto.request.RejectCourseRequest;
import com.edumy.authoring.dto.response.PendingCourseResponse;
import com.edumy.authoring.dto.response.ReviewStatusResponse;
import com.edumy.authoring.service.CourseModerationService;
import com.edumy.authoring.service.CourseQueryService;
import com.edumy.shared.security.CurrentUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PagedModel;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Course review by the admin (UC-A09…A12, UC-A16). Admin only: route rule {@code /api/v1/admin/**} plus the
 * method-level check (docs/ai/04 §4.3); the writes also re-check the admin role in the database (BR-26). The content of
 * a pending course (UC-A10 step 1) is read through {@code GET /api/v1/learn/courses/{courseId}} and
 * {@code GET /api/v1/learn/lessons/{lessonId}} (admin without enrollment, D-32).
 * <p>Every write answers 200 + the course's {@link ReviewStatusResponse} after the change; a course not in the
 * starting status of the transition → 409 {@code COURSE_INVALID_TRANSITION} (e.g. already decided by another admin,
 * UC-A10 E1); unknown id → 404.
 */
@RestController
@RequestMapping("/api/v1/admin/courses")
@Tag(name = "authoring")
@PreAuthorize("hasRole('ADMIN')")
public class AdminCourseReviewController {

    private final CourseQueryService queries;
    private final CourseModerationService moderation;

    public AdminCourseReviewController(CourseQueryService queries, CourseModerationService moderation) {
        this.queries = queries;
        this.moderation = moderation;
    }

    /** {@code page} (0-based), {@code size} (default 20, max 100); oldest submission first ({@code sort} ignored). */
    @GetMapping("/pending")
    @Operation(summary = "UC-A09 Xem danh sách khóa học chờ duyệt (gửi sớm nhất đứng trước)")
    public PagedModel<PendingCourseResponse> pending(@ParameterObject Pageable pageable) {
        return new PagedModel<>(queries.pendingQueue(pageable));
    }

    /** pending_review → published (reviewer, review time, first publication time); mail to the main instructor. */
    @PostMapping("/{id}/approve")
    @Operation(summary = "UC-A10 Phê duyệt khóa học (chờ duyệt → công khai)")
    public ReviewStatusResponse approve(@PathVariable Long id, @CurrentUserId Long adminId) {
        moderation.approve(adminId, id);
        return queries.reviewStatus(id);
    }

    /** pending_review → rejected; {@code reason} required (BR-08) → 400 when blank; mail to the main instructor. */
    @PostMapping("/{id}/reject")
    @Operation(summary = "UC-A11 Từ chối khóa học (bắt buộc nhập lý do)")
    public ReviewStatusResponse reject(@PathVariable Long id, @CurrentUserId Long adminId,
                                       @Valid @RequestBody RejectCourseRequest request) {
        moderation.reject(adminId, id, request.reason());
        return queries.reviewStatus(id);
    }

    /** published → hidden; {@code reason} required (BR-08); enrolled students keep access (BR-31); mail. */
    @PostMapping("/{id}/hide")
    @Operation(summary = "UC-A12 Ẩn khóa học đang công khai (bắt buộc nhập lý do)")
    public ReviewStatusResponse hide(@PathVariable Long id, @CurrentUserId Long adminId,
                                     @Valid @RequestBody HideCourseRequest request) {
        moderation.hide(adminId, id, request.reason());
        return queries.reviewStatus(id);
    }

    /** hidden → published (back in the catalog, D-20). No body, no mail. */
    @PostMapping("/{id}/unhide")
    @Operation(summary = "UC-A16 Hiển thị lại khóa học đã ẩn")
    public ReviewStatusResponse unhide(@PathVariable Long id, @CurrentUserId Long adminId) {
        moderation.unhide(adminId, id);
        return queries.reviewStatus(id);
    }
}
