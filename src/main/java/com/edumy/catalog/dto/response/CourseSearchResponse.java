package com.edumy.catalog.dto.response;

import java.util.List;

import org.springframework.data.web.PagedModel;

/**
 * {@code GET /catalog/courses} (UC-G01–G03): the {@code PagedModel} shape of docs/ai/05 §5.1 ({@code content} +
 * {@code page}) plus {@code suggestions}. UC-G02 "no result → suggest the newest courses": when a keyword search
 * ({@code q}) finds nothing at all, {@code content} is empty and {@code suggestions} holds the newest published
 * courses; in every other case {@code suggestions} is an empty list.
 */
public record CourseSearchResponse(
        List<CourseCardResponse> content,
        PagedModel.PageMetadata page,
        List<CourseCardResponse> suggestions) {

    public CourseSearchResponse {
        content = List.copyOf(content);
        suggestions = List.copyOf(suggestions);
    }
}
