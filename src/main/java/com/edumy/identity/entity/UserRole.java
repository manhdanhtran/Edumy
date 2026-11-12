package com.edumy.identity.entity;

import java.time.Instant;
import java.util.Objects;

import com.edumy.shared.error.BusinessException;
import com.edumy.shared.error.ErrorCode;
import com.edumy.shared.jpa.JpaEntities;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * {@code user_roles} (EDU-DDD-001 §5.3). Rows are never deleted: revoke sets {@code is_active = false} and a later
 * grant reactivates the same row (uq_user_roles_user_role, D-04). The "self-registration is blocked once revoked" rule
 * (D-04) is a service decision based on {@link #isActive()}; only an admin may {@link #reactivate}.
 */
@Entity
@Table(name = "user_roles")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserRole {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "role_id", nullable = false, updatable = false)
    private Role role;

    /** Admin who assigned the role; null for self-service grants (registration, UC-G13). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_by")
    private User assignedBy;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "assigned_at", nullable = false)
    private Instant assignedAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    /** First assignment of {@code role} to {@code user}; {@code assignedBy} is null for self-service grants. */
    public static UserRole grant(User user, Role role, User assignedBy, Instant now) {
        UserRole userRole = new UserRole();
        userRole.user = Objects.requireNonNull(user, "user");
        userRole.role = Objects.requireNonNull(role, "role");
        userRole.assignedBy = assignedBy;
        userRole.active = true;
        userRole.assignedAt = Objects.requireNonNull(now, "now");
        return userRole;
    }

    /** UC-A05 (D-07): active → inactive only. */
    public void revoke() {
        if (!active) {
            throw new BusinessException(ErrorCode.DATA_CONFLICT);
        }
        active = false;
    }

    /**
     * UC-A04 on a previously revoked row: inactive → active only, by an admin (D-04: a revoked role cannot be
     * self-reactivated). {@code assigned_by}/{@code assigned_at} then describe the current assignment.
     */
    public void reactivate(User admin, Instant now) {
        Objects.requireNonNull(admin, "admin");
        if (active) {
            throw new BusinessException(ErrorCode.DATA_CONFLICT);
        }
        active = true;
        assignedBy = admin;
        assignedAt = Objects.requireNonNull(now, "now");
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
        return id != null && id.equals(((UserRole) o).getId());
    }

    @Override
    public final int hashCode() {
        return JpaEntities.effectiveClass(this).hashCode();
    }

    @Override
    public String toString() {
        return "UserRole[id=" + getId() + ", active=" + active + "]";
    }
}
