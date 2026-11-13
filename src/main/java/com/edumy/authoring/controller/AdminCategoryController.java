package com.edumy.authoring.controller;

import java.util.List;

import com.edumy.authoring.dto.response.AdminCategoryResponse;
import com.edumy.authoring.service.CategoryQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Category administration (UC-A06…A08). Admin only: route rule {@code /api/v1/admin/**} plus the method-level check
 * (docs/ai/04 §4.3).
 */
@RestController
@RequestMapping("/api/v1/admin/categories")
@Tag(name = "authoring")
@PreAuthorize("hasRole('ADMIN')")
public class AdminCategoryController {

    private final CategoryQueryService queries;

    public AdminCategoryController(CategoryQueryService queries) {
        this.queries = queries;
    }

    /** Active and inactive categories by name, with the number of courses using each (D-21); not paged. */
    @GetMapping
    @Operation(summary = "UC-A06 UC-A07 UC-A08 Xem tất cả danh mục (kể cả ngừng sử dụng) và số khóa học đang dùng")
    public List<AdminCategoryResponse> list() {
        return queries.listAll();
    }
}
