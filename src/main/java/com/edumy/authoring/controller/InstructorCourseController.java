package com.edumy.authoring.controller;

import java.net.URI;

import com.edumy.authoring.dto.request.CreateCourseRequest;
import com.edumy.authoring.dto.request.UpdateCourseRequest;
import com.edumy.authoring.dto.response.InstructorCourseDetailResponse;
import com.edumy.authoring.dto.response.InstructorCourseSummaryResponse;
import com.edumy.authoring.dto.response.ReviewStatusResponse;
import com.edumy.authoring.service.CourseQueryService;
import com.edumy.authoring.service.CourseService;
import com.edumy.authoring.service.CreateCourseCommand;
import com.edumy.authoring.service.SubmitForReviewService;
import com.edumy.authoring.service.UpdateCourseCommand;
import com.edumy.shared.security.CurrentUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PagedModel;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
    private final CourseService courses;
    private final SubmitForReviewService submissions;

    public InstructorCourseController(CourseQueryService queries, CourseService courses,
                                      SubmitForReviewService submissions) {
        this.queries = queries;
        this.courses = courses;
        this.submissions = submissions;
    }

    /**
     * 201 + the new {@code draft} course (caller = main instructor, unique slug from the title). 422
     * {@code COURSE_CATEGORY_UNAVAILABLE} (category unknown or inactive); 400 {@code VALIDATION_FAILED} (BR-07 price…).
     */
    @PostMapping
    @PreAuthorize("@courseAccess.isActiveInstructor()")
    @Operation(summary = "UC-I01 Tạo khóa học (bản nháp, người tạo là giảng viên chính)")
    public ResponseEntity<InstructorCourseDetailResponse> create(@CurrentUserId Long userId,
                                                                 @Valid @RequestBody CreateCourseRequest request) {
        Long id = courses.create(userId, new CreateCourseCommand(request.title(), request.description(),
                request.thumbnailUrl(), request.categoryId(), request.price()));
        return ResponseEntity.created(URI.create("/api/v1/instructor/courses/" + id)).body(queries.detail(id, userId));
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

    /**
     * Main instructor only (BR-06; co-instructor or stranger → 404). Only the fields sent change; the slug never does.
     * 200 + the course · 409 {@code COURSE_NOT_EDITABLE} (pending_review, BR-33) · 422
     * {@code COURSE_CATEGORY_UNAVAILABLE} · 400 {@code VALIDATION_FAILED}. Published / hidden: effective at once (D-14).
     */
    @PatchMapping("/{id}")
    @PreAuthorize("@courseAccess.isMain(#id)")
    @Operation(summary = "UC-I03 Cập nhật thông tin khóa học (tên, mô tả, ảnh, danh mục, giá)")
    public InstructorCourseDetailResponse update(@PathVariable Long id, @CurrentUserId Long userId,
                                                 @Valid @RequestBody UpdateCourseRequest request) {
        courses.update(id, new UpdateCourseCommand(request.title(), request.description(), request.thumbnailUrl(),
                request.categoryId(), request.price()));
        return queries.detail(id, userId);
    }

    /**
     * Main instructor only. 204 = deleted with its chapters, lessons and invitations · 409
     * {@code COURSE_DELETE_FORBIDDEN} (published once, BR-19) · 409 {@code COURSE_NOT_EDITABLE} (pending_review).
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("@courseAccess.isMain(#id)")
    @Operation(summary = "UC-I04 Xóa khóa học chưa từng công khai")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        courses.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Main instructor only (BR-06, E2: co-instructor / stranger → 404). 200 + the review status ({@code pending_review},
     * {@code submittedAt} = now) · 422 {@code COURSE_SUBMIT_CHECKLIST_FAILED} with one {@code errors[]} entry per failed
     * BR-07 item ({@code title}, {@code description}, {@code categoryId}, {@code price}, {@code chapters},
     * {@code emptyChapters}, {@code lessonContent}) · 409 {@code COURSE_INVALID_TRANSITION} (not draft / rejected).
     */
    @PostMapping("/{id}/submit")
    @PreAuthorize("@courseAccess.isMain(#id)")
    @Operation(summary = "UC-I17 Gửi yêu cầu duyệt khóa học (kiểm tra checklist BR-07)")
    public ReviewStatusResponse submit(@PathVariable Long id) {
        submissions.submit(id);
        return queries.reviewStatus(id);
    }

    @GetMapping("/{id}/review-status")
    @PreAuthorize("@courseAccess.isInstructor(#id)")
    @Operation(summary = "UC-I18 Xem trạng thái duyệt khóa học (thời điểm gửi, xử lý, lý do từ chối/ẩn gần nhất)")
    public ReviewStatusResponse reviewStatus(@PathVariable Long id) {
        return queries.reviewStatus(id);
    }
}
