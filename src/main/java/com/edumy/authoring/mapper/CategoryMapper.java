package com.edumy.authoring.mapper;

import com.edumy.authoring.dto.response.AdminCategoryResponse;
import com.edumy.authoring.repository.CategoryRepository.AdminCategoryView;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface CategoryMapper {

    /** UC-A06…A08 admin list row, {@code courseCount} counted by the query (D-21). */
    AdminCategoryResponse toAdminResponse(AdminCategoryView category);
}
