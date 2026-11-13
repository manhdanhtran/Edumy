package com.edumy.catalog.repository;

import java.util.List;

import com.edumy.catalog.dto.response.CategoryResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Catalog read model of {@code categories} (ADR-003): SELECT only, mapped straight into response records. */
@Repository
public class CategoryCatalogRepository {

    private final JdbcClient jdbc;

    public CategoryCatalogRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** UC-G03: active categories (single level, D-09) by name (collation order), id as tie-breaker. */
    public List<CategoryResponse> findActive() {
        return jdbc.sql("SELECT id, name, slug FROM categories WHERE is_active ORDER BY name, id")
                .query((rs, n) -> new CategoryResponse(rs.getLong("id"), rs.getString("name"), rs.getString("slug")))
                .list();
    }
}
