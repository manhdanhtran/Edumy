package com.edumy.catalog.service;

import java.util.List;

import com.edumy.catalog.dto.response.CategoryResponse;
import com.edumy.catalog.repository.CategoryCatalogRepository;
import com.edumy.shared.config.CacheConfig;
import org.springframework.cache.annotation.Cacheable;
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

    /**
     * UC-G03: the active categories (an inactive one is not offered as a filter). Cached 1 h under the key
     * {@code 'active'} (docs/ai/05 §5.3); {@code CategoryChanged} evicts it after commit. The cached list is
     * unmodifiable (a cache hit hands the same instance to every caller).
     */
    @Cacheable(cacheNames = CacheConfig.CATALOG_CATEGORIES, key = "'active'")
    public List<CategoryResponse> activeCategories() {
        return List.copyOf(categories.findActive());
    }
}
