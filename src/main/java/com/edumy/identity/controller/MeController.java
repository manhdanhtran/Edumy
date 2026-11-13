package com.edumy.identity.controller;

import com.edumy.identity.dto.request.ChangePasswordRequest;
import com.edumy.identity.dto.request.UpdateProfileRequest;
import com.edumy.identity.dto.response.MeResponse;
import com.edumy.identity.service.InstructorRegistrationService;
import com.edumy.identity.service.InstructorRegistrationService.InstructorRegistrationResult;
import com.edumy.identity.service.ProfileService;
import com.edumy.shared.security.AuthenticatedUser;
import com.edumy.shared.security.CurrentUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The caller's own account (UC-G09, UC-G12, UC-G13). Needs login; the user is always the token's {@code sub}. */
@RestController
@RequestMapping("/api/v1/me")
@Tag(name = "identity")
public class MeController {

    private final ProfileService profiles;
    private final InstructorRegistrationService instructorRegistration;

    public MeController(ProfileService profiles, InstructorRegistrationService instructorRegistration) {
        this.profiles = profiles;
        this.instructorRegistration = instructorRegistration;
    }

    @GetMapping
    @Operation(summary = "UC-G09 Xem hồ sơ của tôi (vai trò đang hiệu lực đọc từ CSDL)")
    public MeResponse getMe(@CurrentUserId Long userId) {
        return profiles.getMe(userId);
    }

    @PatchMapping
    @Operation(summary = "UC-G09 Cập nhật họ tên (email không sửa được)")
    public MeResponse updateProfile(@CurrentUserId Long userId, @Valid @RequestBody UpdateProfileRequest request) {
        return profiles.updateFullName(userId, request.fullName());
    }

    @PutMapping("/password")
    @Operation(summary = "UC-G12 Đổi mật khẩu, kết thúc các phiên khác (phiên hiện tại giữ nguyên)")
    public ResponseEntity<Void> changePassword(@CurrentUserId Long userId,
                                               @Valid @RequestBody ChangePasswordRequest request) {
        String currentSid = AuthenticatedUser.require().sessionId();
        profiles.changePassword(userId, currentSid, request.currentPassword(), request.newPassword());
        return ResponseEntity.noContent().build();
    }

    /**
     * 201 = instructor role granted now; 200 = already active (UC-G13 E3, idempotent). Both return the profile with
     * the roles of the database; the SPA calls {@code /auth/refresh} to get an access token carrying the new role.
     */
    @PostMapping("/instructor-registration")
    @Operation(summary = "UC-G13 Đăng ký làm giảng viên (cần email đã xác thực)")
    public ResponseEntity<MeResponse> registerAsInstructor(@CurrentUserId Long userId) {
        InstructorRegistrationResult result = instructorRegistration.register(userId);
        return ResponseEntity.status(result.granted() ? HttpStatus.CREATED : HttpStatus.OK).body(result.me());
    }
}
