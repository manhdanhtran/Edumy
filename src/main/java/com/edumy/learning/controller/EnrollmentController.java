package com.edumy.learning.controller;

import com.edumy.learning.dto.response.EnrollmentResponse;
import com.edumy.learning.service.EnrollmentQueryService;
import com.edumy.learning.service.EnrollmentService;
import com.edumy.shared.security.CurrentUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PagedModel;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The caller's enrollments (UC-S08) and free enrollment (UC-S15), scoped by the caller's id. */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "learning")
public class EnrollmentController {

    private final EnrollmentQueryService queries;
    private final EnrollmentService enrollments;

    public EnrollmentController(EnrollmentQueryService queries, EnrollmentService enrollments) {
        this.queries = queries;
        this.enrollments = enrollments;
    }

    /** {@code page}, {@code size} (default 20, max 100); newest enrollment first ({@code sort} is ignored). */
    @GetMapping("/me/enrollments")
    @Operation(summary = "UC-S08 Xem khóa đã ghi danh (tiến độ %, bài truy cập gần nhất, chứng chỉ)")
    public PagedModel<EnrollmentResponse> mine(@CurrentUserId Long userId, @ParameterObject Pageable pageable) {
        return new PagedModel<>(queries.listMine(userId, pageable));
    }

    /**
     * 201 + the new enrollment; already enrolled → 200 + the existing one (UC-S15 step 4). 404 not published or unknown
     * · 422 not free / the caller instructs the course. No verified email needed (D-02).
     */
    @PostMapping("/courses/{courseId}/enrollments")
    @Operation(summary = "UC-S15 Ghi danh khóa học miễn phí")
    public ResponseEntity<EnrollmentResponse> enroll(@CurrentUserId Long userId, @PathVariable Long courseId) {
        boolean created = enrollments.enrollFree(userId, courseId);
        EnrollmentResponse body = queries.findMine(userId, courseId);
        if (!created) {
            return ResponseEntity.ok(body);
        }
        return ResponseEntity.created(URI.create("/api/v1/me/enrollments")).body(body);
    }
}
