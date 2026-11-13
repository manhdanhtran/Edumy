package com.edumy.catalog.controller;

import java.util.List;

import com.edumy.catalog.dto.response.CategoryResponse;
import com.edumy.catalog.service.CategoryCatalogQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public category list for the catalog filter (UC-G03). No login needed. */
@RestController
@RequestMapping("/api/v1/catalog/categories")
@Tag(name = "catalog")
public class CatalogCategoryController {

    private final CategoryCatalogQueryService queries;

    public CatalogCategoryController(CategoryCatalogQueryService queries) {
        this.queries = queries;
    }

    @GetMapping
    @Operation(summary = "UC-G03 Xem danh mục đang hoạt động (bộ lọc khóa học)")
    public List<CategoryResponse> list() {
        return queries.activeCategories();
    }
}
