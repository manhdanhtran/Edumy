package com.edumy.catalog.service;

import java.util.List;

import com.edumy.catalog.dto.response.CategoryResponse;
import com.edumy.catalog.repository.CategoryCatalogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Public category list (UC-G03 filter) over the catalog read model (ADR-003). */
@Service
@Transactional(readOnly = true)
public class CategoryCatalogQueryService {

    private final CategoryCatalogRepository categories;

    public CategoryCatalogQueryService(CategoryCatalogRepository categories) {
        this.categories = categories;
    }

    /** UC-G03: the active categories (an inactive one is not offered as a filter). */
    public List<CategoryResponse> activeCategories() {
        return categories.findActive();
    }
}
