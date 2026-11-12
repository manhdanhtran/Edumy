package com.edumy.identity.entity;

import java.time.Instant;

import com.edumy.shared.security.RoleCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/**
 * {@code roles} (EDU-DDD-001 §5.2). Reference data inserted by V6__reference_data.sql; the application never writes it.
 */
@Entity
@Immutable
@Table(name = "roles")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Role {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, length = 30)
    private RoleCode code;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Override
    public final boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || User.effectiveClass(this) != User.effectiveClass(o)) {
            return false;
        }
        Long id = getId();
        return id != null && id.equals(((Role) o).getId());
    }

    @Override
    public final int hashCode() {
        return User.effectiveClass(this).hashCode();
    }

    @Override
    public String toString() {
        return "Role[id=" + getId() + ", code=" + code + "]";
    }
}
