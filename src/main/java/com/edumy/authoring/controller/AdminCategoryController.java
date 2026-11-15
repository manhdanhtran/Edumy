package com.edumy.authoring.controller;

import java.net.URI;
import java.util.List;

import com.edumy.authoring.dto.request.CategoryRequest;
import com.edumy.authoring.dto.response.AdminCategoryResponse;
import com.edumy.authoring.service.CategoryQueryService;
import com.edumy.authoring.service.CategoryService;
import com.edumy.shared.security.CurrentUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
 * Category administration (UC-A06…A08). Admin only: route rule {@code /api/v1/admin/**} plus the method-level check
 * (docs/ai/04 §4.3); the writes also re-check the admin role in the database (BR-26 → 403 {@code ACCESS_DENIED}).
 */
@RestController
@RequestMapping("/api/v1/admin/categories")
@Tag(name = "authoring")
@PreAuthorize("hasRole('ADMIN')")
public class AdminCategoryController {

    private final CategoryQueryService queries;
    private final CategoryService categories;

    public AdminCategoryController(CategoryQueryService queries, CategoryService categories) {
        this.queries = queries;
        this.categories = categories;
    }

    /** Active and inactive categories by name, with the number of courses using each (D-21); not paged. */
    @GetMapping
    @Operation(summary = "UC-A06 UC-A07 UC-A08 Xem tất cả danh mục (kể cả ngừng sử dụng) và số khóa học đang dùng")
    public List<AdminCategoryResponse> list() {
        return queries.listAll();
    }

    /**
     * 201 + the new category ({@code slug} generated from the name when absent, active unless {@code active:false});
     * 409 {@code CATEGORY_ALREADY_EXISTS} (name or slug taken); 400 {@code VALIDATION_FAILED}.
     */
    @PostMapping
    @Operation(summary = "UC-A06 Thêm danh mục khóa học (tên, slug duy nhất; mặc định đang hoạt động)")
    public ResponseEntity<AdminCategoryResponse> create(@CurrentUserId Long adminId,
                                                        @Valid @RequestBody CategoryRequest request) {
        Long id = categories.create(adminId, request.name(), request.slug(), request.active());
        return ResponseEntity.created(URI.create("/api/v1/admin/categories/" + id)).body(queries.get(id));
    }

    /**
     * 200 + the category; only the fields sent change (the slug stays when absent; {@code active} deactivates /
     * reactivates). 409 {@code CATEGORY_ALREADY_EXISTS}; 404 unknown id.
     */
    @PatchMapping("/{id}")
    @Operation(summary = "UC-A07 Cập nhật danh mục (tên, slug, trạng thái hoạt động)")
    public AdminCategoryResponse update(@PathVariable Long id, @CurrentUserId Long adminId,
                                        @Valid @RequestBody CategoryRequest request) {
        categories.update(adminId, id, request.name(), request.slug(), request.active());
        return queries.get(id);
    }

    /**
     * D-21: 204 = deleted (no course used it) · 200 + the category with {@code active:false} = used by courses, only
     * deactivated (they keep it) · 409 {@code DATA_CONFLICT} = used and already inactive · 404 unknown id.
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "UC-A08 Xóa danh mục chưa dùng hoặc ngừng sử dụng danh mục đã có khóa học")
    public ResponseEntity<AdminCategoryResponse> delete(@PathVariable Long id, @CurrentUserId Long adminId) {
        if (categories.deleteOrDeactivate(adminId, id)) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(queries.get(id));
    }
}
