package com.edumy.identity.entity;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import com.edumy.shared.error.BusinessException;
import com.edumy.shared.error.ErrorCode;
import com.edumy.shared.error.FieldViolation;
import com.edumy.shared.jpa.JpaEntities;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * {@code users} (EDU-DDD-001 §5.1). State machine: active ⇄ locked (docs/ai/02 §2.2); email verification is the
 * one-way flag false → true (UC-G06). BR-36 (≥1 active admin, never lock self / another admin) needs other rows and
 * is enforced by the admin service, not here.
 */
@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Always normalized (trimmed + lower-case, ck_users_email_normalized); column is utf8mb4_bin. */
    @Column(name = "email", nullable = false, length = 255, updatable = false)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "full_name", nullable = false, length = 100)
    private String fullName;

    @Column(name = "is_email_verified", nullable = false)
    private boolean emailVerified;

    @Column(name = "status", nullable = false, length = 20)
    private UserStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Maintained by MySQL (ON UPDATE CURRENT_TIMESTAMP(6)); null on a freshly persisted instance until reloaded. */
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    /** Normal form of an email used for storage and every lookup: trimmed, lower-case (ck_users_email_normalized). */
    public static String normalizeEmail(String email) {
        Objects.requireNonNull(email, "email");
        return email.trim().toLowerCase(Locale.ROOT);
    }

    /** UC-G05: new account, status active, email not verified yet. {@code email} is normalized here. */
    public static User register(String email, String passwordHash, String fullName, Instant now) {
        String normalized = normalizeEmail(email);
        if (normalized.isEmpty()) {
            throw invalid("email", "Email không được để trống");
        }
        User user = new User();
        user.email = normalized;
        user.passwordHash = requirePasswordHash(passwordHash);
        user.fullName = requireFullName(fullName);
        user.emailVerified = false;
        user.status = UserStatus.ACTIVE;
        user.createdAt = Objects.requireNonNull(now, "now");
        return user;
    }

    /** UC-G06: false → true only; verifying twice is rejected (the token was already consumed). */
    public void verifyEmail() {
        if (emailVerified) {
            throw new BusinessException(ErrorCode.DATA_CONFLICT);
        }
        emailVerified = true;
    }

    /** UC-G08 / UC-G12. The password policy and "new ≠ current" are checked by the service on the raw password. */
    public void changePasswordHash(String newPasswordHash) {
        passwordHash = requirePasswordHash(newPasswordHash);
    }

    /** UC-G09. Stored trimmed (ck_users_full_name). */
    public void updateFullName(String newFullName) {
        fullName = requireFullName(newFullName);
    }

    /** UC-A02: active → locked only. BR-36 guards and ending sessions (D-05) belong to the service. */
    public void lock() {
        if (status != UserStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.DATA_CONFLICT);
        }
        status = UserStatus.LOCKED;
    }

    /** UC-A03: locked → active only. */
    public void unlock() {
        if (status != UserStatus.LOCKED) {
            throw new BusinessException(ErrorCode.DATA_CONFLICT);
        }
        status = UserStatus.ACTIVE;
    }

    public boolean isActive() {
        return status == UserStatus.ACTIVE;
    }

    private static String requirePasswordHash(String passwordHash) {
        if (passwordHash == null || passwordHash.isBlank()) {
            throw new IllegalArgumentException("passwordHash must not be blank");
        }
        return passwordHash;
    }

    private static String requireFullName(String fullName) {
        String trimmed = fullName == null ? "" : fullName.strip();
        if (trimmed.isEmpty()) {
            throw invalid("fullName", "Họ tên không được để trống");
        }
        return trimmed;
    }

    private static BusinessException invalid(String field, String message) {
        return BusinessException.withErrors(ErrorCode.VALIDATION_FAILED, List.of(new FieldViolation(field, message)));
    }

    @Override
    public final boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || JpaEntities.effectiveClass(this) != JpaEntities.effectiveClass(o)) {
            return false;
        }
        Long id = getId();
        return id != null && id.equals(((User) o).getId());
    }

    @Override
    public final int hashCode() {
        return JpaEntities.effectiveClass(this).hashCode();
    }

    @Override
    public String toString() {
        return "User[id=" + getId() + ", status=" + status + "]";
    }
}
