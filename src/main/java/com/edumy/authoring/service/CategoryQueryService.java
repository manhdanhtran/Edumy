package com.edumy.authoring.service;

import java.util.List;

import com.edumy.authoring.dto.response.AdminCategoryResponse;
import com.edumy.authoring.mapper.CategoryMapper;
import com.edumy.authoring.repository.CategoryRepository;
import com.edumy.shared.error.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Admin reads of categories (UC-A06…A08). The public list of active categories belongs to catalog (UC-G03). */
@Service
@Transactional(readOnly = true)
public class CategoryQueryService {

    private final CategoryRepository categories;
    private final CategoryMapper mapper;

    public CategoryQueryService(CategoryRepository categories, CategoryMapper mapper) {
        this.categories = categories;
        this.mapper = mapper;
    }

    /** Every category, active and inactive, by name, with the number of courses using it (D-21). One statement. */
    public List<AdminCategoryResponse> listAll() {
        return categories.findAllWithCourseCount().stream().map(mapper::toAdminResponse).toList();
    }

    /** One category as in {@link #listAll()} (answer of the UC-A06…A08 writes); unknown id → 404. One statement. */
    public AdminCategoryResponse get(Long categoryId) {
        return categories.findWithCourseCountById(categoryId).map(mapper::toAdminResponse)
                .orElseThrow(BusinessException::notFound);
    }
}
