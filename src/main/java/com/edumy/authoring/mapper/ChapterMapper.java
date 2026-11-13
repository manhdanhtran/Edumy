package com.edumy.authoring.mapper;

import java.util.List;

import com.edumy.authoring.dto.response.ChapterResponse;
import com.edumy.authoring.dto.response.LessonResponse;
import com.edumy.authoring.entity.Chapter;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface ChapterMapper {

    /** {@code lessons}: the chapter's lessons, already mapped and in display order. */
    ChapterResponse toResponse(Chapter chapter, List<LessonResponse> lessons);
}
