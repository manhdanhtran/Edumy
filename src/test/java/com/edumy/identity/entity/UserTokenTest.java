package com.edumy.identity.entity;

import static com.edumy.identity.entity.UserTest.NOW;
import static com.edumy.identity.entity.UserTest.assertConflict;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class UserTokenTest {

    static final String HASH = "0123456789abcdef".repeat(4);

    private final User user = UserTest.newUser();

    @Test
    void issue_emailVerification_expiresAfter24h_BR34() {
        UserToken token = UserToken.issue(user, HASH, TokenType.EMAIL_VERIFICATION, NOW);

        assertThat(token.getUser()).isSameAs(user);
        assertThat(token.getTokenHash()).isEqualTo(HASH);
        assertThat(token.getTokenType()).isEqualTo(TokenType.EMAIL_VERIFICATION);
        assertThat(token.getCreatedAt()).isEqualTo(NOW);
        assertThat(token.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofHours(24)));
        assertThat(token.getUsedAt()).isNull();
    }

    @Test
    void issue_passwordReset_expiresAfter30min_BR34() {
        UserToken token = UserToken.issue(user, HASH, TokenType.PASSWORD_RESET, NOW);

        assertThat(token.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(30)));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF", // upper case
            "0123456789abcdef", "g123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"})
    void issue_notSha256Hex_rejected(String hash) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> UserToken.issue(user, hash, TokenType.PASSWORD_RESET, NOW));
    }

    @Test
    void isUsable_freshToken_true() {
        UserToken token = UserToken.issue(user, HASH, TokenType.PASSWORD_RESET, NOW);

        assertThat(token.isUsable(NOW)).isTrue();
        assertThat(token.isUsable(token.getExpiresAt().minusNanos(1000))).isTrue();
    }

    @Test
    void isUsable_atOrAfterExpiry_false() {
        UserToken token = UserToken.issue(user, HASH, TokenType.PASSWORD_RESET, NOW);

        assertThat(token.isUsable(token.getExpiresAt())).isFalse();
        assertThat(token.isUsable(token.getExpiresAt().plusSeconds(1))).isFalse();
    }

    @Test
    void markUsed_usable_setsUsedAt_BR34() {
        UserToken token = UserToken.issue(user, HASH, TokenType.EMAIL_VERIFICATION, NOW);
        var usedAt = NOW.plusSeconds(60);

        token.markUsed(usedAt);

        assertThat(token.getUsedAt()).isEqualTo(usedAt);
        assertThat(token.isUsable(usedAt)).isFalse();
    }

    @Test
    void markUsed_alreadyUsed_rejected_BR34() {
        UserToken token = UserToken.issue(user, HASH, TokenType.EMAIL_VERIFICATION, NOW);
        token.markUsed(NOW.plusSeconds(1));

        assertConflict(() -> token.markUsed(NOW.plusSeconds(2)));
        assertThat(token.getUsedAt()).isEqualTo(NOW.plusSeconds(1));
    }

    @Test
    void markUsed_expired_rejected_BR34() {
        UserToken token = UserToken.issue(user, HASH, TokenType.PASSWORD_RESET, NOW);

        assertConflict(() -> token.markUsed(token.getExpiresAt()));
        assertThat(token.getUsedAt()).isNull();
    }
}
