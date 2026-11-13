package com.edumy.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;

import com.edumy.identity.api.RoleQueryApi;
import com.edumy.shared.config.CacheConfig;
import com.edumy.shared.security.RoleCode;
import com.edumy.support.MySqlSpringBootTest;
import com.edumy.support.TestUsers;
import com.edumy.support.TestUsers.TestUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** {@link RoleQueryApi} on MySQL: cached per user (BR-26), evicted after commit by {@link RoleCacheEvictor}. */
@MySqlSpringBootTest
class RoleQueryApiImplIT {

    @Autowired
    RoleQueryApi roleQuery;
    @Autowired
    RoleCacheEvictor evictor;
    @Autowired
    InstructorRegistrationService instructorRegistration;
    @Autowired
    CacheManager cacheManager;
    @Autowired
    PlatformTransactionManager transactionManager;
    @Autowired
    TestUsers users;

    Cache cache;

    @BeforeEach
    void setUp() {
        cache = cacheManager.getCache(CacheConfig.ACTIVE_ROLES);
    }

    @AfterEach
    void cleanUp() {
        users.deleteAll();
    }

    @Test
    void activeRoles_cachedImmutableSetKeyedByUserId_secondCallServedFromCache() {
        TestUser user = users.create(RoleCode.STUDENT, RoleCode.INSTRUCTOR);

        assertThat(roleQuery.activeRoles(user.id())).containsExactlyInAnyOrder(RoleCode.STUDENT, RoleCode.INSTRUCTOR);
        Cache.ValueWrapper cached = cache.get(user.id()); // key = the Long user id (InstructorRegistrationService)
        assertThat(cached).isNotNull();
        @SuppressWarnings("unchecked")
        Set<RoleCode> value = (Set<RoleCode>) cached.get();
        assertThat(value).containsExactlyInAnyOrder(RoleCode.STUDENT, RoleCode.INSTRUCTOR);
        assertThatThrownBy(() -> value.add(RoleCode.ADMIN)).isInstanceOf(UnsupportedOperationException.class);

        // changed in the database behind the cache's back: the answer still comes from the cache (no query)
        users.revokeRole(user.id(), RoleCode.INSTRUCTOR);
        assertThat(roleQuery.hasActiveRole(user.id(), RoleCode.INSTRUCTOR)).isTrue();
        assertThat(roleQuery.activeRoles(user.id())).isSameAs(value);

        evictor.evict(user.id()); // no transaction → immediate
        assertThat(cache.get(user.id())).isNull();
        assertThat(roleQuery.hasActiveRole(user.id(), RoleCode.INSTRUCTOR)).isFalse();
        assertThat(roleQuery.activeRoles(user.id())).containsExactly(RoleCode.STUDENT);
    }

    @Test
    void activeRoles_unknownUser_emptySet() {
        assertThat(roleQuery.activeRoles(Long.MAX_VALUE)).isEmpty();
        assertThat(roleQuery.hasActiveRole(Long.MAX_VALUE, RoleCode.STUDENT)).isFalse();
        cache.evict(Long.MAX_VALUE);
    }

    @Test
    void instructorRegistration_evictsAfterCommit_hasActiveRoleTrueImmediately_UCG13() {
        TestUser user = users.create(RoleCode.STUDENT);
        assertThat(roleQuery.hasActiveRole(user.id(), RoleCode.INSTRUCTOR)).isFalse(); // now cached without it

        instructorRegistration.register(user.id());

        assertThat(cache.get(user.id())).isNull();
        assertThat(roleQuery.hasActiveRole(user.id(), RoleCode.INSTRUCTOR)).isTrue();
    }

    @Test
    void evict_insideRolledBackTransaction_keepsTheEntry() {
        TestUser user = users.create(RoleCode.STUDENT);
        roleQuery.activeRoles(user.id());

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            evictor.evict(user.id());
            assertThat(cache.get(user.id())).as("not evicted before commit").isNotNull();
            status.setRollbackOnly();
        });

        assertThat(cache.get(user.id())).isNotNull();
        cache.evict(user.id());
    }
}
