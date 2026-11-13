package com.edumy.identity.controller;

import java.time.Duration;

import com.edumy.identity.dto.request.ForgotPasswordRequest;
import com.edumy.identity.dto.request.LoginRequest;
import com.edumy.identity.dto.request.RegisterRequest;
import com.edumy.identity.dto.request.ResetPasswordRequest;
import com.edumy.identity.dto.request.VerifyEmailRequest;
import com.edumy.identity.dto.response.AuthTokenResponse;
import com.edumy.identity.dto.response.ForgotPasswordResponse;
import com.edumy.identity.service.AuthService;
import com.edumy.identity.service.SessionService;
import com.edumy.identity.service.SessionTokens;
import com.edumy.shared.security.AuthenticatedUser;
import com.edumy.shared.security.CurrentUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Session endpoints (docs/ai/04 §4.1, ADR-002). The access token goes in the body; the refresh token only in the
 * cookie {@value #REFRESH_COOKIE} ({@code HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth}), rotated on every
 * refresh. Origin checks of refresh/logout: {@code identity.security.AuthOriginFilter}.
 * <p>Account endpoints (UC-G05, UC-G06, UC-G08): register logs the new user in (201 + cookie); confirm, forgot and
 * reset are public; resend needs login (Q2). Mails are sent by the notification module after commit.
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "identity")
public class AuthController {

    public static final String REFRESH_COOKIE = "edumy_rt";
    static final String REFRESH_COOKIE_PATH = "/api/v1/auth";

    private final SessionService sessions;
    private final AuthService auth;

    public AuthController(SessionService sessions, AuthService auth) {
        this.sessions = sessions;
        this.auth = auth;
    }

    @PostMapping("/register")
    @Operation(summary = "UC-G05 Đăng ký tài khoản (vai trò học viên), gửi mã xác thực email và đăng nhập luôn")
    public ResponseEntity<AuthTokenResponse> register(@Valid @RequestBody RegisterRequest request,
                                                      HttpServletRequest http) {
        SessionTokens tokens = auth.register(request.email(), request.password(), request.fullName(),
                userAgent(http), http.getRemoteAddr());
        return withRefreshCookie(HttpStatus.CREATED, tokens);
    }

    @PostMapping("/email-verification/confirm")
    @Operation(summary = "UC-G06 Xác thực email bằng mã trong liên kết (không cần đăng nhập)")
    public ResponseEntity<Void> confirmEmail(@Valid @RequestBody VerifyEmailRequest request) {
        auth.confirmEmail(request.token());
        return ResponseEntity.noContent().build();
    }

    /** 202 = a new code was issued and will be mailed; 204 = the email is already verified, nothing sent. */
    @PostMapping("/email-verification/resend")
    @Operation(summary = "UC-G06 Gửi lại mã xác thực email (cần đăng nhập, cách nhau 60 giây)")
    public ResponseEntity<Void> resendVerification(@CurrentUserId Long userId) {
        boolean sent = auth.resendVerification(userId);
        return sent ? ResponseEntity.accepted().build() : ResponseEntity.noContent().build();
    }

    /** D-03: always the same 202 and body, whether or not anything was sent. */
    @PostMapping("/password/forgot")
    @Operation(summary = "UC-G08 Quên mật khẩu: gửi liên kết đặt lại (luôn trả cùng một thông báo)")
    public ResponseEntity<ForgotPasswordResponse> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        auth.forgotPassword(request.email());
        return ResponseEntity.accepted().body(ForgotPasswordResponse.generic());
    }

    @PostMapping("/password/reset")
    @Operation(summary = "UC-G08 Đặt lại mật khẩu bằng mã trong liên kết, kết thúc mọi phiên, không tự đăng nhập")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        auth.resetPassword(request.token(), request.newPassword());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/login")
    @Operation(summary = "UC-G07 Đăng nhập bằng email và mật khẩu")
    public ResponseEntity<AuthTokenResponse> login(@Valid @RequestBody LoginRequest request,
                                                   HttpServletRequest http) {
        SessionTokens tokens = sessions.login(request.email(), request.password(), userAgent(http),
                http.getRemoteAddr());
        return withRefreshCookie(HttpStatus.OK, tokens);
    }

    @PostMapping("/refresh")
    @Operation(summary = "UC-G07 Cấp lại access token bằng refresh token trong cookie (xoay vòng refresh token)")
    public ResponseEntity<AuthTokenResponse> refresh(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken, HttpServletRequest http) {
        SessionTokens tokens = sessions.refresh(refreshToken, userAgent(http), http.getRemoteAddr());
        return withRefreshCookie(HttpStatus.OK, tokens);
    }

    @PostMapping("/logout")
    @Operation(summary = "UC-G10 Đăng xuất phiên hiện tại")
    public ResponseEntity<Void> logout() {
        sessions.logout(AuthenticatedUser.require().sessionId());
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, refreshCookie("", Duration.ZERO).toString())
                .build();
    }

    private static ResponseEntity<AuthTokenResponse> withRefreshCookie(HttpStatus status, SessionTokens tokens) {
        return ResponseEntity.status(status)
                .header(HttpHeaders.SET_COOKIE, refreshCookie(tokens.refreshToken(), tokens.refreshTtl()).toString())
                .body(tokens.body());
    }

    /** Max-Age = refresh TTL; {@link Duration#ZERO} deletes the cookie (same attributes, so the browser matches it). */
    private static ResponseCookie refreshCookie(String value, Duration maxAge) {
        return ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path(REFRESH_COOKIE_PATH)
                .maxAge(maxAge)
                .build();
    }

    private static String userAgent(HttpServletRequest http) {
        return http.getHeader(HttpHeaders.USER_AGENT);
    }
}
