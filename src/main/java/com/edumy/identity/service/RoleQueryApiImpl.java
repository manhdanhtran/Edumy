package com.edumy.identity.service;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

import com.edumy.identity.api.RoleQueryApi;
import com.edumy.identity.repository.UserRoleRepository;
import com.edumy.shared.config.CacheConfig;
import com.edumy.shared.security.RoleCode;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;

/**
 * {@link RoleQueryApi} through the {@value CacheConfig#ACTIVE_ROLES} cache (60 s, key = user id {@code Long}, value =
 * an immutable {@code Set<RoleCode>}). Uses the {@link Cache} API directly (no {@code @Cacheable}), so
 * {@link #hasActiveRole} reads the same cached set without the proxy self-invocation trap. Evicted by
 * {@link RoleCacheEvictor}.
 */
@Service
class RoleQueryApiImpl implements RoleQueryApi {

    private final Cache activeRoles;
    private final UserRoleRepository userRoles;

    RoleQueryApiImpl(CacheManager cacheManager, UserRoleRepository userRoles) {
        this.activeRoles = requireCache(cacheManager);
        this.userRoles = userRoles;
    }

    @Override
    public Set<RoleCode> activeRoles(Long userId) {
        Objects.requireNonNull(userId, "userId");
        return activeRoles.get(userId, () -> load(userId));
    }

    @Override
    public boolean hasActiveRole(Long userId, RoleCode role) {
        Objects.requireNonNull(role, "role");
        return activeRoles(userId).contains(role);
    }

    private Set<RoleCode> load(Long userId) {
        Set<RoleCode> roles = EnumSet.noneOf(RoleCode.class);
        roles.addAll(userRoles.findActiveRoleCodesByUserId(userId));
        return Set.copyOf(roles);
    }

    static Cache requireCache(CacheManager cacheManager) {
        Cache cache = cacheManager.getCache(CacheConfig.ACTIVE_ROLES);
        if (cache == null) {
            throw new IllegalStateException(
                    "Cache " + CacheConfig.ACTIVE_ROLES + " is not configured (shared.config.CacheConfig)");
        }
        return cache;
    }
}
