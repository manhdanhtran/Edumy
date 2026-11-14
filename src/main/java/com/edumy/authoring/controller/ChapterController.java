package com.edumy.authoring.controller;

import java.net.URI;
import java.util.List;

import com.edumy.authoring.dto.request.CreateChapterRequest;
import com.edumy.authoring.dto.request.ReorderRequest;
import com.edumy.authoring.dto.request.UpdateChapterRequest;
import com.edumy.authoring.dto.response.ChapterResponse;
import com.edumy.authoring.service.ChapterService;
import com.edumy.authoring.service.CourseQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Chapters of a course (UC-I05…I08), for the main instructor and accepted co-instructors (BR-04) holding an
 * <b>active</b> instructor role in the database (BR-26: else 403). A course / chapter the caller does not teach answers
 * 404 like an unknown id (NFR-01); a {@code pending_review} course 409 {@code COURSE_NOT_EDITABLE} (BR-33).
 */
@RestController
@RequestMapping("/api/v1/instructor")
@Tag(name = "authoring")
public class ChapterController {

    private final ChapterService chapters;
    private final CourseQueryService queries;

    public ChapterController(ChapterService chapters, CourseQueryService queries) {
        this.chapters = chapters;
        this.queries = queries;
    }

    /** 201 + the new chapter (last position, no lessons). */
    @PostMapping("/courses/{id}/chapters")
    @PreAuthorize("@courseAccess.canEditContent(#id)")
    @Operation(summary = "UC-I05 Thêm chương học (vào cuối khóa)")
    public ResponseEntity<ChapterResponse> create(@PathVariable Long id,
                                                  @Valid @RequestBody CreateChapterRequest request) {
        Long chapterId = chapters.create(id, request.title());
        return ResponseEntity.created(URI.create("/api/v1/instructor/chapters/" + chapterId))
                .body(queries.chapter(chapterId));
    }

    /** 200 + the chapter with its lessons. */
    @PatchMapping("/chapters/{id}")
    @PreAuthorize("@chapterAccess.canEditContent(#id)")
    @Operation(summary = "UC-I06 Cập nhật chương học (tên chương)")
    public ChapterResponse update(@PathVariable Long id, @Valid @RequestBody UpdateChapterRequest request) {
        chapters.rename(id, request.title());
        return queries.chapter(id);
    }

    /**
     * 204 = deleted with its lessons and their progress; the following chapters move up. 409
     * {@code COURSE_LAST_LESSON_DELETE_FORBIDDEN} when a published / hidden course would keep no lesson (D-14).
     */
    @DeleteMapping("/chapters/{id}")
    @PreAuthorize("@chapterAccess.canEditContent(#id)")
    @Operation(summary = "UC-I07 Xóa chương học cùng các bài học")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        chapters.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * {@code ids} = every chapter of the course once, in the new order → 200 + the outline (chapters 1..n with their
     * lessons). 400 {@code VALIDATION_FAILED} on {@code ids} when an id is missing, foreign or repeated.
     */
    @PutMapping("/courses/{id}/chapters/order")
    @PreAuthorize("@courseAccess.canEditContent(#id)")
    @Operation(summary = "UC-I08 Sắp xếp chương học")
    public List<ChapterResponse> reorder(@PathVariable Long id, @Valid @RequestBody ReorderRequest request) {
        chapters.reorder(id, request.ids());
        return queries.outline(id);
    }
}
