package com.edumy.identity.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import com.edumy.identity.entity.RefreshToken;
import com.edumy.identity.entity.RevokeReason;
import com.edumy.identity.entity.Role;
import com.edumy.identity.entity.TokenType;
import com.edumy.identity.entity.User;
import com.edumy.identity.entity.UserRole;
import com.edumy.identity.entity.UserStatus;
import com.edumy.identity.entity.UserToken;
import com.edumy.identity.repository.UserRepository.AdminUserView;
import com.edumy.identity.repository.UserRoleRepository.UserRoleCodeView;
import com.edumy.shared.security.RoleCode;
import com.edumy.shared.text.LikePattern;
import com.edumy.support.MySqlSpringBootTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;

/**
 * Identity repositories on real MySQL 8.4 (database {@code edumy}, no seed). Every test runs in a rolled-back
 * transaction and uses unique emails / token hashes, so nothing is left in the shared database (docs/ai/07).
 */
@MySqlSpringBootTest
@Transactional
class IdentityRepositoryIT {

    static final Instant NOW = Instant.parse("2026-01-15T10:20:30.123456Z");
    static final String PASSWORD_HASH = "$2a$12$abcdefghijklmnopqrstuvabcdefghijklmnopqrstuvwxyz01234";

    @Autowired
    EntityManager em;
    @Autowired
    UserRepository users;
    @Autowired
    RoleRepository roles;
    @Autowired
    UserRoleRepository userRoles;
    @Autowired
    UserTokenRepository userTokens;
    @Autowired
    RefreshTokenRepository refreshTokens;

    // ---------------------------------------------------------------------------------------------------------- users

    @Test
    void findByEmail_normalizedEmail_found_mixedCaseNotFound() {
        String marker = uniqueMarker();
        User user = users.saveAndFlush(User.register("  " + marker.toUpperCase() + "@Example.COM ", PASSWORD_HASH,
                "Nguyễn Văn A", NOW));
        em.clear();

        assertThat(users.findByEmail(User.normalizeEmail(marker + "@example.com"))).get()
                .extracting(User::getId).isEqualTo(user.getId());
        assertThat(users.existsByEmail(marker + "@example.com")).isTrue();
        // utf8mb4_bin: lookups are exact, callers must normalize first
        assertThat(users.findByEmail(marker.toUpperCase() + "@example.com")).isEmpty();
    }

    @Test
    void save_user_storesUtcInstantAndDbManagedUpdatedAt() {
        User user = users.saveAndFlush(User.register(uniqueEmail(), PASSWORD_HASH, "A", NOW));

        // the raw DATETIME(6) text, no driver conversion: must be the UTC wall time (docs/ai/03 §3.1)
        Object createdAt = em.createNativeQuery(
                        "SELECT DATE_FORMAT(created_at, '%Y-%m-%dT%H:%i:%s.%f') FROM users WHERE id = ?1")
                .setParameter(1, user.getId()).getSingleResult();
        assertThat(createdAt).isEqualTo(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC).toString());

        em.clear();
        User reloaded = users.findById(user.getId()).orElseThrow();
        assertThat(reloaded.getCreatedAt()).isEqualTo(NOW);
        assertThat(reloaded.getUpdatedAt()).isNotNull();
        assertThat(reloaded.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(reloaded.isEmailVerified()).isFalse();
    }

    @Test
    void lockAndVerify_persisted_statusCodesStored() {
        User user = users.saveAndFlush(User.register(uniqueEmail(), PASSWORD_HASH, "A", NOW));
        user.lock();
        user.verifyEmail();
        users.flush();

        Object status = em.createNativeQuery("SELECT status FROM users WHERE id = ?1")
                .setParameter(1, user.getId()).getSingleResult();
        assertThat(status).isEqualTo("locked");
        em.clear();
        assertThat(users.findByIdForUpdate(user.getId())).get()
                .satisfies(u -> {
                    assertThat(u.getStatus()).isEqualTo(UserStatus.LOCKED);
                    assertThat(u.isEmailVerified()).isTrue();
                });
    }

    @Test
    void searchForAdmin_filters_qStatusRole_UCA01() {
        String marker = uniqueMarker();
        User student = newUser(marker + "-s@example.com", "Học Viên " + marker);
        User instructor = newUser(marker + "-i@example.com", "Giảng Viên " + marker);
        User locked = newUser(marker + "-l@example.com", "Bị Khóa " + marker);
        grant(student, RoleCode.STUDENT);
        grant(instructor, RoleCode.STUDENT);
        grant(instructor, RoleCode.INSTRUCTOR);
        UserRole revoked = grant(locked, RoleCode.INSTRUCTOR);
        revoked.revoke();
        locked.lock();
        em.flush();
        em.clear();
        PageRequest page = PageRequest.of(0, 10, Sort.by("email"));

        Page<AdminUserView> byQ = users.searchForAdmin(LikePattern.contains(marker), null, null, page);
        assertThat(byQ.getTotalElements()).isEqualTo(3);
        assertThat(byQ.getContent()).extracting(AdminUserView::getEmail)
                .containsExactly(marker + "-i@example.com", marker + "-l@example.com", marker + "-s@example.com");
        AdminUserView first = byQ.getContent().getFirst();
        assertThat(first.getId()).isEqualTo(instructor.getId());
        assertThat(first.getFullName()).isEqualTo("Giảng Viên " + marker);
        assertThat(first.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(first.isEmailVerified()).isFalse();
        assertThat(first.getCreatedAt()).isEqualTo(NOW);

        // q is case-insensitive on the email (lower-cased) and case/accent-insensitive on full_name (ai_ci)
        assertThat(users.searchForAdmin(LikePattern.contains(marker.toUpperCase() + "-S@"), null, null, page).getContent())
                .extracting(AdminUserView::getId).containsExactly(student.getId());
        assertThat(users.searchForAdmin(LikePattern.contains("giang vien " + marker), null, null, page).getContent())
                .extracting(AdminUserView::getId).containsExactly(instructor.getId());

        assertThat(users.searchForAdmin(LikePattern.contains(marker), UserStatus.LOCKED, null, page).getContent())
                .extracting(AdminUserView::getId).containsExactly(locked.getId());
        // revoked roles do not count
        assertThat(users.searchForAdmin(LikePattern.contains(marker), null, RoleCode.INSTRUCTOR, page).getContent())
                .extracting(AdminUserView::getId).containsExactly(instructor.getId());
        assertThat(users.searchForAdmin(LikePattern.contains(marker), UserStatus.ACTIVE, RoleCode.STUDENT, page).getTotalElements())
                .isEqualTo(2);

        Page<AdminUserView> paged = users.searchForAdmin(LikePattern.contains(marker), null, null,
                PageRequest.of(1, 2, Sort.by("email")));
        assertThat(paged.getTotalElements()).isEqualTo(3);
        assertThat(paged.getContent()).extracting(AdminUserView::getId).containsExactly(student.getId());

        // MINOR-4 (P10): % and _ of the keyword are literals (ESCAPE '!'), never wildcards
        assertThat(users.searchForAdmin(LikePattern.contains(marker + "%"), null, null, page).getTotalElements())
                .isZero();
        assertThat(users.searchForAdmin(LikePattern.contains(marker + "_s@"), null, null, page).getTotalElements())
                .isZero();
        assertThat(users.searchForAdmin(LikePattern.contains(marker + "!"), null, null, page).getTotalElements())
                .isZero();
        User odd = newUser(marker + "_x%y!z@example.com", "Ký Tự " + marker);
        em.flush();
        assertThat(users.searchForAdmin(LikePattern.contains(marker + "_x%y!z"), null, null, page).getContent())
                .extracting(AdminUserView::getId).containsExactly(odd.getId());
        // no keyword = no filter
        assertThat(users.searchForAdmin(null, null, null, PageRequest.of(0, 1)).getTotalElements())
                .isGreaterThanOrEqualTo(4);
    }

    // ----------------------------------------------------------------------------------------------- roles, user_roles

    @Test
    void findByCode_referenceData_allRolesPresent() {
        for (RoleCode code : RoleCode.values()) {
            assertThat(roles.findByCode(code)).get().extracting(Role::getCode).isEqualTo(code);
        }
    }

    @Test
    void activeRoleQueries_revokedRoleExcluded_BR26() {
        User user = newUser(uniqueEmail(), "A");
        User other = newUser(uniqueEmail(), "B");
        grant(user, RoleCode.STUDENT);
        grant(user, RoleCode.INSTRUCTOR).revoke();
        grant(other, RoleCode.INSTRUCTOR);
        em.flush();
        em.clear();

        assertThat(userRoles.findActiveRoleCodesByUserId(user.getId())).containsExactly(RoleCode.STUDENT);
        assertThat(userRoles.existsActiveRole(user.getId(), RoleCode.STUDENT)).isTrue();
        assertThat(userRoles.existsActiveRole(user.getId(), RoleCode.INSTRUCTOR)).isFalse();
        assertThat(userRoles.existsActiveRole(other.getId(), RoleCode.INSTRUCTOR)).isTrue();

        List<UserRoleCodeView> page = userRoles.findActiveRoleCodesByUserIdIn(List.of(user.getId(), other.getId()));
        assertThat(page).extracting(UserRoleCodeView::getUserId, UserRoleCodeView::getRoleCode).containsExactly(
                org.assertj.core.groups.Tuple.tuple(user.getId(), RoleCode.STUDENT),
                org.assertj.core.groups.Tuple.tuple(other.getId(), RoleCode.INSTRUCTOR));

        // the revoked row is still found (D-04: re-grant reactivates the same row)
        assertThat(userRoles.findByUserIdAndRoleCode(user.getId(), RoleCode.INSTRUCTOR)).get()
                .satisfies(ur -> assertThat(ur.isActive()).isFalse());
        assertThat(userRoles.findByUserIdAndRoleCode(user.getId(), RoleCode.ADMIN)).isEmpty();
    }

    @Test
    void reactivate_persisted_assignedByAndAssignedAtStored_D04() {
        User admin = newUser(uniqueEmail(), "Admin");
        User user = newUser(uniqueEmail(), "A");
        UserRole userRole = grant(user, RoleCode.INSTRUCTOR);
        userRole.revoke();
        em.flush();

        userRole.reactivate(admin, NOW.plusSeconds(60));
        em.flush();
        em.clear();

        UserRole reloaded = userRoles.findByUserIdAndRoleCode(user.getId(), RoleCode.INSTRUCTOR).orElseThrow();
        assertThat(reloaded.isActive()).isTrue();
        assertThat(reloaded.getAssignedBy().getId()).isEqualTo(admin.getId());
        assertThat(reloaded.getAssignedAt()).isEqualTo(NOW.plusSeconds(60));
    }

    @Test
    void countActiveHolders_activeAdminsOnly_BR36() {
        long before = userRoles.countActiveHolders(RoleCode.ADMIN, UserStatus.ACTIVE);
        User admin = newUser(uniqueEmail(), "Admin");
        User lockedAdmin = newUser(uniqueEmail(), "Locked admin");
        User revokedAdmin = newUser(uniqueEmail(), "Revoked admin");
        grant(admin, RoleCode.ADMIN);
        grant(lockedAdmin, RoleCode.ADMIN);
        lockedAdmin.lock();
        grant(revokedAdmin, RoleCode.ADMIN).revoke();
        em.flush();

        assertThat(userRoles.countActiveHolders(RoleCode.ADMIN, UserStatus.ACTIVE)).isEqualTo(before + 1);
    }

    // ---------------------------------------------------------------------------------------------------- user_tokens

    @Test
    void consume_T7_firstCall1_secondCall0_BR34() {
        User user = newUser(uniqueEmail(), "A");
        String hash = randomHash();
        userTokens.saveAndFlush(UserToken.issue(user, hash, TokenType.EMAIL_VERIFICATION, NOW));
        Instant at = NOW.plusSeconds(30);

        assertThat(userTokens.consume(hash, TokenType.EMAIL_VERIFICATION, at)).isEqualTo(1);
        assertThat(userTokens.consume(hash, TokenType.EMAIL_VERIFICATION, at)).isZero();

        assertThat(userTokens.findUserIdByTokenHashAndType(hash, TokenType.EMAIL_VERIFICATION)).contains(user.getId());
        UserToken used = userTokens.findAll().stream().filter(t -> t.getTokenHash().equals(hash)).findFirst()
                .orElseThrow();
        assertThat(used.getUsedAt()).isEqualTo(at);
    }

    @Test
    void consume_T7_expired0_wrongType0_unknown0_BR34() {
        User user = newUser(uniqueEmail(), "A");
        String hash = randomHash();
        UserToken token = userTokens.saveAndFlush(UserToken.issue(user, hash, TokenType.PASSWORD_RESET, NOW));

        assertThat(userTokens.consume(hash, TokenType.PASSWORD_RESET, token.getExpiresAt())).isZero();
        assertThat(userTokens.consume(hash, TokenType.PASSWORD_RESET, NOW.plus(Duration.ofHours(1)))).isZero();
        assertThat(userTokens.consume(hash, TokenType.EMAIL_VERIFICATION, NOW.plusSeconds(1))).isZero();
        assertThat(userTokens.consume(randomHash(), TokenType.PASSWORD_RESET, NOW.plusSeconds(1))).isZero();
        // still usable just before expiry
        assertThat(userTokens.consume(hash, TokenType.PASSWORD_RESET, token.getExpiresAt().minusNanos(1000)))
                .isEqualTo(1);
    }

    @Test
    void invalidateUnused_newTokenVoidsOld_onlySameUserAndType_BR34() {
        User user = newUser(uniqueEmail(), "A");
        User other = newUser(uniqueEmail(), "B");
        String old1 = randomHash();
        String old2 = randomHash();
        String reset = randomHash();
        String othersToken = randomHash();
        userTokens.save(UserToken.issue(user, old1, TokenType.EMAIL_VERIFICATION, NOW));
        userTokens.save(UserToken.issue(user, old2, TokenType.EMAIL_VERIFICATION, NOW.plusSeconds(61)));
        userTokens.save(UserToken.issue(user, reset, TokenType.PASSWORD_RESET, NOW));
        userTokens.save(UserToken.issue(other, othersToken, TokenType.EMAIL_VERIFICATION, NOW));
        Instant at = NOW.plusSeconds(200);

        assertThat(userTokens.invalidateUnused(user.getId(), TokenType.EMAIL_VERIFICATION, at)).isEqualTo(2);

        assertThat(userTokens.consume(old1, TokenType.EMAIL_VERIFICATION, at)).isZero();
        assertThat(userTokens.consume(old2, TokenType.EMAIL_VERIFICATION, at)).isZero();
        assertThat(userTokens.consume(reset, TokenType.PASSWORD_RESET, NOW.plusSeconds(1))).isEqualTo(1);
        assertThat(userTokens.consume(othersToken, TokenType.EMAIL_VERIFICATION, at)).isEqualTo(1);
    }

    @Test
    void findLatestCreatedAt_resendCooldown_latestOfUserAndType_D03() {
        User user = newUser(uniqueEmail(), "A");
        assertThat(userTokens.findLatestCreatedAt(user.getId(), TokenType.EMAIL_VERIFICATION)).isEmpty();

        userTokens.save(UserToken.issue(user, randomHash(), TokenType.EMAIL_VERIFICATION, NOW));
        userTokens.save(UserToken.issue(user, randomHash(), TokenType.EMAIL_VERIFICATION, NOW.plusSeconds(90)));
        userTokens.save(UserToken.issue(user, randomHash(), TokenType.PASSWORD_RESET, NOW.plusSeconds(500)));
        userTokens.flush();

        assertThat(userTokens.findLatestCreatedAt(user.getId(), TokenType.EMAIL_VERIFICATION))
                .contains(NOW.plusSeconds(90));
    }

    // ------------------------------------------------------------------------------------------------- refresh_tokens

    @Test
    void findByTokenHash_savedToken_foundWithAllColumns() {
        User user = newUser(uniqueEmail(), "A");
        String family = UUID.randomUUID().toString();
        String hash = randomHash();
        refreshTokens.saveAndFlush(RefreshToken.issue(user, family, hash, Duration.ofDays(14), "UA", "::1", NOW));
        em.clear();

        RefreshToken token = refreshTokens.findForUpdateByTokenHash(hash).orElseThrow();
        assertThat(token.getFamilyId()).isEqualTo(family);
        assertThat(token.getUser().getId()).isEqualTo(user.getId());
        assertThat(token.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(14)));
        assertThat(token.isActive(NOW)).isTrue();
        assertThat(refreshTokens.findByTokenHash(randomHash())).isEmpty();
    }

    @Test
    void revoke_entity_persistsReasonAndTime_rotationKeepsFamily() {
        User user = newUser(uniqueEmail(), "A");
        String family = UUID.randomUUID().toString();
        String hash = randomHash();
        RefreshToken current = refreshTokens.saveAndFlush(
                RefreshToken.issue(user, family, hash, Duration.ofDays(14), null, null, NOW));

        current.revoke(RevokeReason.ROTATED, NOW.plusSeconds(5));
        refreshTokens.save(RefreshToken.issue(user, family, randomHash(), Duration.ofDays(14), null, null,
                NOW.plusSeconds(5)));
        refreshTokens.flush();
        em.clear();

        RefreshToken reloaded = refreshTokens.findByTokenHash(hash).orElseThrow();
        assertThat(reloaded.getRevokeReason()).isEqualTo(RevokeReason.ROTATED);
        assertThat(reloaded.getRevokedAt()).isEqualTo(NOW.plusSeconds(5));
        assertThat(refreshTokens.existsByFamilyIdAndRevokedAtIsNull(family)).isTrue();
    }

    @Test
    void revokeFamily_logout_revokesOnlyActiveRowsOfFamily_firstReasonKept() {
        User user = newUser(uniqueEmail(), "A");
        String family = UUID.randomUUID().toString();
        String otherFamily = UUID.randomUUID().toString();
        String rotated = randomHash();
        String current = randomHash();
        RefreshToken old = refreshTokens.save(issue(user, family, rotated));
        old.revoke(RevokeReason.ROTATED, NOW.plusSeconds(1));
        refreshTokens.save(issue(user, family, current));
        refreshTokens.save(issue(user, otherFamily, randomHash()));

        assertThat(refreshTokens.revokeFamily(family, RevokeReason.LOGOUT, NOW.plusSeconds(9))).isEqualTo(1);

        assertThat(refreshTokens.existsByFamilyIdAndRevokedAtIsNull(family)).isFalse();
        assertThat(refreshTokens.existsByFamilyIdAndRevokedAtIsNull(otherFamily)).isTrue();
        assertThat(refreshTokens.findByTokenHash(rotated).orElseThrow().getRevokeReason())
                .isEqualTo(RevokeReason.ROTATED);
        RefreshToken revoked = refreshTokens.findByTokenHash(current).orElseThrow();
        assertThat(revoked.getRevokeReason()).isEqualTo(RevokeReason.LOGOUT);
        assertThat(revoked.getRevokedAt()).isEqualTo(NOW.plusSeconds(9));
        assertThat(refreshTokens.revokeFamily(family, RevokeReason.LOGOUT, NOW.plusSeconds(10))).isZero();
    }

    @Test
    void revokeAllOfUserExceptFamily_changePassword_keepsCurrentSession_UCG12() {
        User user = newUser(uniqueEmail(), "A");
        User other = newUser(uniqueEmail(), "B");
        String keep = UUID.randomUUID().toString();
        String familyB = UUID.randomUUID().toString();
        String familyC = UUID.randomUUID().toString();
        String othersFamily = UUID.randomUUID().toString();
        refreshTokens.save(issue(user, keep, randomHash()));
        refreshTokens.save(issue(user, familyB, randomHash()));
        refreshTokens.save(issue(user, familyC, randomHash()));
        refreshTokens.save(issue(other, othersFamily, randomHash()));

        assertThat(refreshTokens.revokeAllOfUserExceptFamily(user.getId(), keep, RevokeReason.PASSWORD_CHANGED,
                NOW.plusSeconds(1))).isEqualTo(2);

        assertThat(refreshTokens.existsByFamilyIdAndRevokedAtIsNull(keep)).isTrue();
        assertThat(refreshTokens.existsByFamilyIdAndRevokedAtIsNull(familyB)).isFalse();
        assertThat(refreshTokens.existsByFamilyIdAndRevokedAtIsNull(familyC)).isFalse();
        assertThat(refreshTokens.existsByFamilyIdAndRevokedAtIsNull(othersFamily)).isTrue();
    }

    @Test
    void revokeAllOfUser_lockOrReset_revokesEveryFamilyOfUserOnly_D05() {
        User user = newUser(uniqueEmail(), "A");
        User other = newUser(uniqueEmail(), "B");
        String familyA = UUID.randomUUID().toString();
        String familyB = UUID.randomUUID().toString();
        String othersFamily = UUID.randomUUID().toString();
        String hashA = randomHash();
        refreshTokens.save(issue(user, familyA, hashA));
        refreshTokens.save(issue(user, familyB, randomHash()));
        refreshTokens.save(issue(other, othersFamily, randomHash()));

        assertThat(refreshTokens.revokeAllOfUser(user.getId(), RevokeReason.ACCOUNT_LOCKED, NOW.plusSeconds(1)))
                .isEqualTo(2);

        assertThat(refreshTokens.existsByFamilyIdAndRevokedAtIsNull(familyA)).isFalse();
        assertThat(refreshTokens.existsByFamilyIdAndRevokedAtIsNull(familyB)).isFalse();
        assertThat(refreshTokens.existsByFamilyIdAndRevokedAtIsNull(othersFamily)).isTrue();
        Object reason = em.createNativeQuery("SELECT revoke_reason FROM refresh_tokens WHERE token_hash = ?1")
                .setParameter(1, hashA).getSingleResult();
        assertThat(reason).isEqualTo("account_locked");
    }

    // -------------------------------------------------------------------------------------------------------- helpers

    private User newUser(String email, String fullName) {
        return users.save(User.register(email, PASSWORD_HASH, fullName, NOW));
    }

    private UserRole grant(User user, RoleCode code) {
        return userRoles.save(UserRole.grant(user, roles.findByCode(code).orElseThrow(), null, NOW));
    }

    private static RefreshToken issue(User user, String family, String hash) {
        return RefreshToken.issue(user, family, hash, Duration.ofDays(14), null, null, NOW);
    }

    private static String uniqueMarker() {
        return "it-" + UUID.randomUUID().toString().substring(0, 13);
    }

    private static String uniqueEmail() {
        return uniqueMarker() + "@example.com";
    }

    private static String randomHash() {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
