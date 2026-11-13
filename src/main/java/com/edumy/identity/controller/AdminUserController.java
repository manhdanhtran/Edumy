package com.edumy.identity.controller;

import com.edumy.identity.dto.request.AdminUserSearchRequest;
import com.edumy.identity.dto.response.AdminUserResponse;
import com.edumy.identity.service.UserAdminQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PagedModel;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
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

    public AdminUserController(UserAdminQueryService queries) {
        this.queries = queries;
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
}
