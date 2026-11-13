package com.edumy.authoring.controller;

import java.util.List;

import com.edumy.authoring.dto.response.InvitationResponse;
import com.edumy.authoring.service.InvitationQueryService;
import com.edumy.shared.security.CurrentUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Co-instructor invitations received by the caller (UC-I14, UC-I15). The invitee must hold an <b>active</b> instructor
 * role in the database (UC-I14 precondition, BR-26): otherwise 403.
 */
@RestController
@RequestMapping("/api/v1/instructor/invitations")
@Tag(name = "authoring")
public class InvitationController {

    private final InvitationQueryService queries;

    public InvitationController(InvitationQueryService queries) {
        this.queries = queries;
    }

    /** Pending invitations only, newest first; not paged (an instructor has few pending invitations). */
    @GetMapping
    @PreAuthorize("@courseAccess.isActiveInstructor()")
    @Operation(summary = "UC-I14 Xem lời mời đồng giảng viên đang chờ phản hồi")
    public List<InvitationResponse> pending(@CurrentUserId Long userId) {
        return queries.pendingOf(userId);
    }
}
