package com.edumy.identity.controller;

import java.time.Duration;

import com.edumy.identity.dto.request.LoginRequest;
import com.edumy.identity.dto.response.AuthTokenResponse;
import com.edumy.identity.service.SessionService;
import com.edumy.identity.service.SessionTokens;
import com.edumy.shared.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
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
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "identity")
public class AuthController {

    public static final String REFRESH_COOKIE = "edumy_rt";
    static final String REFRESH_COOKIE_PATH = "/api/v1/auth";

    private final SessionService sessions;

    public AuthController(SessionService sessions) {
        this.sessions = sessions;
    }

    @PostMapping("/login")
    @Operation(summary = "UC-G07 Đăng nhập bằng email và mật khẩu")
    public ResponseEntity<AuthTokenResponse> login(@Valid @RequestBody LoginRequest request,
                                                   HttpServletRequest http) {
        SessionTokens tokens = sessions.login(request.email(), request.password(), userAgent(http),
                http.getRemoteAddr());
        return withRefreshCookie(tokens);
    }

    @PostMapping("/refresh")
    @Operation(summary = "UC-G07 Cấp lại access token bằng refresh token trong cookie (xoay vòng refresh token)")
    public ResponseEntity<AuthTokenResponse> refresh(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken, HttpServletRequest http) {
        SessionTokens tokens = sessions.refresh(refreshToken, userAgent(http), http.getRemoteAddr());
        return withRefreshCookie(tokens);
    }

    @PostMapping("/logout")
    @Operation(summary = "UC-G10 Đăng xuất phiên hiện tại")
    public ResponseEntity<Void> logout() {
        sessions.logout(AuthenticatedUser.require().sessionId());
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, refreshCookie("", Duration.ZERO).toString())
                .build();
    }

    private static ResponseEntity<AuthTokenResponse> withRefreshCookie(SessionTokens tokens) {
        return ResponseEntity.ok()
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
