package com.edumy.identity.controller;

import com.edumy.identity.dto.request.AdminUserSearchRequest;
import com.edumy.identity.dto.response.AdminUserResponse;
import com.edumy.identity.service.UserAdminQueryService;
import com.edumy.identity.service.UserAdminService;
import com.edumy.shared.security.CurrentUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PagedModel;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * User administration (UC-A01…A05). Admin only: route rule {@code /api/v1/admin/**} plus the method-level check
 * (docs/ai/04 §4.3).
 */
@RestController
@RequestMapping("/api/v1/admin/users")
@Tag(name = "identity")
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserController {

    private final UserAdminQueryService queries;
    private final UserAdminService admin;

    public AdminUserController(UserAdminQueryService queries, UserAdminService admin) {
        this.queries = queries;
        this.admin = admin;
    }

    /**
     * {@code page} (0-based), {@code size} (default 20, max 100); newest accounts first ({@code sort} is ignored).
     * Unknown {@code status} / {@code role} → 400 {@code VALIDATION_FAILED}.
     */
    @GetMapping
    @Operation(summary = "UC-A01 Xem danh sách người dùng (tìm theo email/họ tên, lọc trạng thái, vai trò)")
    public PagedModel<AdminUserResponse> search(@Valid @ParameterObject @ModelAttribute AdminUserSearchRequest filter,
                                                @ParameterObject Pageable pageable) {
        return new PagedModel<>(queries.search(filter.q(), filter.status(), filter.role(), pageable));
    }

    /**
     * 200 = locked, every session of the user ended (D-05). 403 {@code ADMIN_LOCK_FORBIDDEN} = the caller or another
     * admin (BR-36); 409 {@code ACCOUNT_ALREADY_LOCKED}; 404 unknown id.
     */
    @PostMapping("/{id}/lock")
    @Operation(summary = "UC-A02 Khóa tài khoản người dùng (kết thúc mọi phiên đăng nhập)")
    public AdminUserResponse lock(@CurrentUserId Long adminId, @PathVariable Long id) {
        return admin.lock(adminId, id);
    }

    /** 200 = active again (the user logs in again); 409 {@code ACCOUNT_NOT_LOCKED}; 404 unknown id. */
    @PostMapping("/{id}/unlock")
    @Operation(summary = "UC-A03 Mở khóa tài khoản người dùng")
    public AdminUserResponse unlock(@CurrentUserId Long adminId, @PathVariable Long id) {
        return admin.unlock(adminId, id);
    }

    /**
     * 200 = instructor role active (new row or the revoked one reactivated); 409 {@code INSTRUCTOR_ROLE_ALREADY_ACTIVE}
     * (E1); 404 unknown id.
     */
    @PutMapping("/{id}/roles/instructor")
    @Operation(summary = "UC-A04 Cấp quyền giảng viên")
    public AdminUserResponse grantInstructor(@CurrentUserId Long adminId, @PathVariable Long id) {
        return admin.grantInstructor(adminId, id);
    }

    /**
     * 200 = instructor role inactive, effective at once (D-07); 409 {@code INSTRUCTOR_ROLE_NOT_ACTIVE} (E1); 404
     * unknown id.
     */
    @DeleteMapping("/{id}/roles/instructor")
    @Operation(summary = "UC-A05 Thu hồi quyền giảng viên (mất quyền biên soạn ngay)")
    public AdminUserResponse revokeInstructor(@CurrentUserId Long adminId, @PathVariable Long id) {
        return admin.revokeInstructor(adminId, id);
    }
}
