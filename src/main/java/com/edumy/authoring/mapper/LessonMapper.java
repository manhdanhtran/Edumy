package com.edumy.authoring.mapper;

import com.edumy.authoring.dto.response.LessonResponse;
import com.edumy.authoring.entity.Lesson;
import com.edumy.authoring.entity.LessonType;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface LessonMapper {

    /** The lesson with its content, for its instructors (never for the public outline). */
    @Mapping(target = "isPreview", source = "preview")
    LessonResponse toResponse(Lesson lesson);

    /** Lower-case DB code ({@code video} / {@code article}). */
    default String contentTypeCode(LessonType type) {
        return type.code();
    }
}
