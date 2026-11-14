package com.edumy.authoring.controller;

import java.util.List;

import com.edumy.authoring.dto.response.InvitationResponse;
import com.edumy.authoring.service.CoInstructorService;
import com.edumy.authoring.service.InvitationQueryService;
import com.edumy.shared.security.CurrentUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Co-instructor invitations received by the caller (UC-I14, UC-I15). The invitee must hold an <b>active</b> instructor
 * role in the database (UC-I14 precondition, BR-26): otherwise 403. An invitation sent to someone else answers 404 like
 * an unknown id (NFR-01).
 */
@RestController
@RequestMapping("/api/v1/instructor/invitations")
@Tag(name = "authoring")
public class InvitationController {

    private final InvitationQueryService queries;
    private final CoInstructorService coInstructors;

    public InvitationController(InvitationQueryService queries, CoInstructorService coInstructors) {
        this.queries = queries;
        this.coInstructors = coInstructors;
    }

    /** Pending invitations only, newest first; not paged (an instructor has few pending invitations). */
    @GetMapping
    @PreAuthorize("@courseAccess.isActiveInstructor()")
    @Operation(summary = "UC-I14 Xem lời mời đồng giảng viên đang chờ phản hồi")
    public List<InvitationResponse> pending(@CurrentUserId Long userId) {
        return queries.pendingOf(userId);
    }

    /** 204 = accepted (co-editing from the next request, BR-04) · 409 {@code INVITATION_NOT_PENDING} (revoked…). */
    @PostMapping("/{id}/accept")
    @PreAuthorize("@courseAccess.isActiveInstructor()")
    @Operation(summary = "UC-I14 Chấp nhận lời mời đồng giảng viên")
    public ResponseEntity<Void> accept(@PathVariable Long id, @CurrentUserId Long userId) {
        coInstructors.accept(id, userId);
        return ResponseEntity.noContent().build();
    }

    /** 204 = declined (no rights, review unaffected, BR-21) · 409 {@code INVITATION_NOT_PENDING}. */
    @PostMapping("/{id}/decline")
    @PreAuthorize("@courseAccess.isActiveInstructor()")
    @Operation(summary = "UC-I15 Từ chối lời mời đồng giảng viên")
    public ResponseEntity<Void> decline(@PathVariable Long id, @CurrentUserId Long userId) {
        coInstructors.decline(id, userId);
        return ResponseEntity.noContent().build();
    }
}
