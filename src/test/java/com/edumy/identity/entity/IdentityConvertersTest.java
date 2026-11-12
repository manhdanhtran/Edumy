package com.edumy.identity.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.edumy.shared.security.RoleCode;
import org.junit.jupiter.api.Test;

/** DB codes must match the CHECK lists of V1__identity.sql exactly. */
class IdentityConvertersTest {

    @Test
    void userStatus_codes_matchCkUsersStatus() {
        assertThat(new UserStatusConverter().convertToDatabaseColumn(UserStatus.LOCKED)).isEqualTo("locked");
        assertThat(new UserStatusConverter().convertToEntityAttribute("active")).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    void tokenType_codes_matchCkUserTokensType() {
        assertThat(new TokenTypeConverter().convertToDatabaseColumn(TokenType.EMAIL_VERIFICATION))
                .isEqualTo("email_verification");
        assertThat(new TokenTypeConverter().convertToEntityAttribute("password_reset"))
                .isEqualTo(TokenType.PASSWORD_RESET);
    }

    @Test
    void revokeReason_codes_matchCkRefreshTokensReason() {
        RevokeReasonConverter converter = new RevokeReasonConverter();
        assertThat(java.util.Arrays.stream(RevokeReason.values()).map(converter::convertToDatabaseColumn))
                .containsExactly("logout", "rotated", "reuse_detected", "password_changed", "password_reset",
                        "account_locked", "expired_cleanup");
    }

    @Test
    void roleCode_codes_matchCkRolesCode() {
        assertThat(new RoleCodeConverter().convertToEntityAttribute("instructor")).isEqualTo(RoleCode.INSTRUCTOR);
        assertThat(new RoleCodeConverter().convertToDatabaseColumn(RoleCode.ADMIN)).isEqualTo("admin");
    }
}
