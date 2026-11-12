package com.edumy.identity.entity;

import static com.edumy.identity.entity.UserTest.NOW;
import static com.edumy.identity.entity.UserTest.assertConflict;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class RefreshTokenTest {

    static final String FAMILY = "3f2504e0-4f89-41d3-9a0c-0305e82c3301";
    static final Duration TTL = Duration.ofDays(14);

    private final User user = UserTest.newUser();

    private RefreshToken issue() {
        return RefreshToken.issue(user, FAMILY, UserTokenTest.HASH, TTL, "Mozilla/5.0", "203.0.113.7", NOW);
    }

    @Test
    void issue_valid_activeUntilTtl() {
        RefreshToken token = issue();

        assertThat(token.getUser()).isSameAs(user);
        assertThat(token.getFamilyId()).isEqualTo(FAMILY);
        assertThat(token.getTokenHash()).isEqualTo(UserTokenTest.HASH);
        assertThat(token.getCreatedAt()).isEqualTo(NOW);
        assertThat(token.getExpiresAt()).isEqualTo(NOW.plus(TTL));
        assertThat(token.getUserAgent()).isEqualTo("Mozilla/5.0");
        assertThat(token.getIpAddress()).isEqualTo("203.0.113.7");
        assertThat(token.getRevokedAt()).isNull();
        assertThat(token.getRevokeReason()).isNull();
        assertThat(token.isRevoked()).isFalse();
    }

    @Test
    void issue_longUserAgentAndIp_truncatedToColumnLength() {
        RefreshToken token = RefreshToken.issue(user, FAMILY, UserTokenTest.HASH, TTL,
                "a".repeat(400), "f".repeat(60), NOW);

        assertThat(token.getUserAgent()).hasSize(RefreshToken.USER_AGENT_MAX);
        assertThat(token.getIpAddress()).hasSize(RefreshToken.IP_ADDRESS_MAX);
    }

    @Test
    void issue_nullUserAgentAndIp_allowed() {
        RefreshToken token = RefreshToken.issue(user, FAMILY, UserTokenTest.HASH, TTL, null, null, NOW);

        assertThat(token.getUserAgent()).isNull();
        assertThat(token.getIpAddress()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-uuid", "3F2504E0-4F89-41D3-9A0C-0305E82C3301", "3f2504e04f8941d39a0c0305e82c3301"})
    void issue_nonCanonicalFamilyId_rejected(String familyId) {
        assertThatIllegalArgumentException().isThrownBy(() ->
                RefreshToken.issue(user, familyId, UserTokenTest.HASH, TTL, null, null, NOW));
    }

    @Test
    void issue_badHashOrTtl_rejected() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                RefreshToken.issue(user, FAMILY, "abc", TTL, null, null, NOW));
        assertThatIllegalArgumentException().isThrownBy(() ->
                RefreshToken.issue(user, FAMILY, UserTokenTest.HASH, Duration.ZERO, null, null, NOW));
        assertThatIllegalArgumentException().isThrownBy(() ->
                RefreshToken.issue(user, FAMILY, UserTokenTest.HASH, null, null, null, NOW));
    }

    @Test
    void isActive_beforeExpiry_true() {
        RefreshToken token = issue();

        assertThat(token.isActive(NOW)).isTrue();
        assertThat(token.isActive(token.getExpiresAt().minusNanos(1000))).isTrue();
    }

    @Test
    void isActive_atOrAfterExpiry_false() {
        RefreshToken token = issue();

        assertThat(token.isActive(token.getExpiresAt())).isFalse();
    }

    @ParameterizedTest
    @EnumSource(RevokeReason.class)
    void revoke_active_setsRevokedAtAndReasonTogether(RevokeReason reason) {
        RefreshToken token = issue();
        var at = NOW.plusSeconds(10);

        token.revoke(reason, at);

        assertThat(token.getRevokedAt()).isEqualTo(at);
        assertThat(token.getRevokeReason()).isEqualTo(reason);
        assertThat(token.isRevoked()).isTrue();
        assertThat(token.isActive(at)).isFalse();
    }

    @Test
    void revoke_expiredButNotRevoked_allowed() {
        RefreshToken token = issue();

        token.revoke(RevokeReason.EXPIRED_CLEANUP, token.getExpiresAt().plusSeconds(1));

        assertThat(token.getRevokeReason()).isEqualTo(RevokeReason.EXPIRED_CLEANUP);
    }

    @Test
    void revoke_alreadyRevoked_rejectedAndFirstReasonKept() {
        RefreshToken token = issue();
        token.revoke(RevokeReason.ROTATED, NOW.plusSeconds(1));

        assertConflict(() -> token.revoke(RevokeReason.LOGOUT, NOW.plusSeconds(2)));
        assertThat(token.getRevokeReason()).isEqualTo(RevokeReason.ROTATED);
        assertThat(token.getRevokedAt()).isEqualTo(NOW.plusSeconds(1));
    }

    @Test
    void revoke_nullReasonOrNow_rejectedAndStillActive() {
        RefreshToken token = issue();

        assertThatNullPointerException().isThrownBy(() -> token.revoke(null, NOW));
        assertThatNullPointerException().isThrownBy(() -> token.revoke(RevokeReason.LOGOUT, null));
        assertThat(token.isRevoked()).isFalse();
    }
}
