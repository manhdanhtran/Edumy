package com.edumy.identity.repository;

import java.util.Optional;

import com.edumy.identity.entity.Role;
import com.edumy.shared.security.RoleCode;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleRepository extends JpaRepository<Role, Long> {

    /** Reference row of a role code (V6__reference_data.sql), e.g. to grant {@code student} at registration. */
    Optional<Role> findByCode(RoleCode code);
}
