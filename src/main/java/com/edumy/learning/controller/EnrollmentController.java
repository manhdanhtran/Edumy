package com.edumy.learning.controller;

import com.edumy.learning.dto.response.EnrollmentResponse;
import com.edumy.learning.service.EnrollmentQueryService;
import com.edumy.shared.security.CurrentUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PagedModel;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The caller's enrollments (UC-S08), scoped by the caller's id. */
@RestController
@RequestMapping("/api/v1/me/enrollments")
@Tag(name = "learning")
public class EnrollmentController {

    private final EnrollmentQueryService queries;

    public EnrollmentController(EnrollmentQueryService queries) {
        this.queries = queries;
    }

    /** {@code page}, {@code size} (default 20, max 100); newest enrollment first ({@code sort} is ignored). */
    @GetMapping
    @Operation(summary = "UC-S08 Xem khóa đã ghi danh (tiến độ %, bài truy cập gần nhất, chứng chỉ)")
    public PagedModel<EnrollmentResponse> mine(@CurrentUserId Long userId, @ParameterObject Pageable pageable) {
        return new PagedModel<>(queries.listMine(userId, pageable));
    }
}
