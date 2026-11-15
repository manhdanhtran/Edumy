package com.edumy.authoring.controller;

import java.net.URI;

import com.edumy.authoring.dto.request.InviteCoInstructorRequest;
import com.edumy.authoring.dto.response.InstructorCourseDetailResponse;
import com.edumy.authoring.service.CoInstructorService;
import com.edumy.authoring.service.CourseQueryService;
import com.edumy.shared.security.CurrentUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Co-instructors of a course (UC-I13 invite, UC-I16 remove / revoke, UC-I20 leave). Every operation needs an
 * <b>active</b> instructor role in the database (BR-26: else 403). Invite and remove: main instructor only (BR-06; a
 * co-instructor or stranger gets 404 like an unknown course, NFR-01). Leave: an accepted co-instructor. No course status
 * blocks these operations (BR-05, BR-21). Resend of an invitation mail (UC-I13 E4, P10) is
 * {@code POST /api/v1/courses/{courseId}/invitations/{invitationId}/resend}.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "authoring")
public class CoInstructorController {

    private final CoInstructorService coInstructors;
    private final CourseQueryService queries;

    public CoInstructorController(CoInstructorService coInstructors, CourseQueryService queries) {
        this.coInstructors = coInstructors;
        this.queries = queries;
    }

    /**
     * 201 + the invitation as listed in the course detail ({@code id} = invited user's id, status {@code pending}),
     * also when a declined / removed invitation is reused (D-18). 422 {@code COURSE_CO_INSTRUCTOR_NOT_ELIGIBLE} (no
     * account or no active instructor role, one answer for both) · 422 {@code COURSE_CO_INSTRUCTOR_IS_MAIN} (BR-02) ·
     * 409 {@code COURSE_CO_INSTRUCTOR_ALREADY_INVITED} (pending / accepted) · 400 {@code VALIDATION_FAILED}.
     */
    @PostMapping("/instructor/courses/{id}/co-instructors")
    @PreAuthorize("@courseAccess.isMain(#id)")
    @Operation(summary = "UC-I13 Mời đồng giảng viên theo email")
    public ResponseEntity<InstructorCourseDetailResponse.CoInstructor> invite(
            @PathVariable Long id, @CurrentUserId Long userId, @Valid @RequestBody InviteCoInstructorRequest request) {
        Long inviteeId = coInstructors.invite(id, userId, request.email());
        return ResponseEntity.created(URI.create("/api/v1/instructor/courses/" + id + "/co-instructors/" + inviteeId))
                .body(queries.coInstructor(id, inviteeId));
    }

    /** 204 = the pending invitation / accepted co-instructor is removed · 404 no pending or accepted invitation. */
    @DeleteMapping("/instructor/courses/{id}/co-instructors/{userId}")
    @PreAuthorize("@courseAccess.isMain(#id)")
    @Operation(summary = "UC-I16 Gỡ đồng giảng viên / thu hồi lời mời")
    public ResponseEntity<Void> remove(@PathVariable Long id, @PathVariable Long userId) {
        coInstructors.remove(id, userId);
        return ResponseEntity.noContent().build();
    }

    /**
     * UC-I13 E4 (P10): 202 = the invitation mail of a pending invitation is sent again (after commit) · 404 unknown
     * invitation / another course's · 409 {@code INVITATION_NOT_PENDING} · 429 {@code INVITATION_RESEND_TOO_SOON}
     * (60 s since the previous mail) with {@code Retry-After}. Main instructor only.
     */
    @PostMapping("/courses/{courseId}/invitations/{invitationId}/resend")
    @PreAuthorize("@courseAccess.isMain(#courseId)")
    @Operation(summary = "UC-I13 Gửi lại thư mời đồng giảng viên (lời mời đang chờ phản hồi)")
    public ResponseEntity<Void> resend(@PathVariable Long courseId, @PathVariable Long invitationId) {
        coInstructors.resend(courseId, invitationId);
        return ResponseEntity.accepted().build();
    }

    /** 204 = left (invitation removed) · 403 {@code COURSE_MAIN_INSTRUCTOR_LEAVE_FORBIDDEN} for the main instructor. */
    @PostMapping("/instructor/courses/{id}/leave")
    @PreAuthorize("@courseAccess.isInstructor(#id)")
    @Operation(summary = "UC-I20 Rời khỏi khóa học cộng tác")
    public ResponseEntity<Void> leave(@PathVariable Long id, @CurrentUserId Long userId) {
        coInstructors.leave(id, userId);
        return ResponseEntity.noContent().build();
    }
}
