package com.edumy.identity.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.edumy.shared.config.EdumyProperties;
import com.edumy.shared.security.RoleCode;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

class JwtIssuerTest {

    static final String SECRET = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes());
    static final Instant NOW = Instant.parse("2026-11-13T08:00:00.987Z");

    private final JwtEncoder encoder = NimbusJwtEncoder.withSecretKey(SecurityConfig.secretKey(SECRET))
            .algorithm(MacAlgorithm.HS256).build();
    /** Signature check only: these tests read the claims, timestamps are checked in JwtSecurityIT. */
    private final NimbusJwtDecoder rawDecoder = NimbusJwtDecoder.withSecretKey(SecurityConfig.secretKey(SECRET))
            .macAlgorithm(MacAlgorithm.HS256).build();

    {
        rawDecoder.setJwtValidator(jwt -> OAuth2TokenValidatorResult.success());
    }

    @Test
    void issue_claims_subRolesSidIatExpIss_noPii() {
        JwtIssuer issuer = new JwtIssuer(encoder, Clock.fixed(NOW, ZoneOffset.UTC), properties(Duration.ofMinutes(15)));
        String sid = UUID.randomUUID().toString();

        JwtIssuer.AccessToken token = issuer.issue(42L, Set.of(RoleCode.ADMIN, RoleCode.STUDENT), sid);
        Jwt jwt = rawDecoder.decode(token.value());

        Instant iat = Instant.parse("2026-11-13T08:00:00Z");
        assertThat(jwt.getSubject()).isEqualTo("42");
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("student", "admin");
        assertThat(jwt.getClaimAsString("sid")).isEqualTo(sid);
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("edumy");
        assertThat(jwt.getIssuedAt()).isEqualTo(iat);
        assertThat(jwt.getExpiresAt()).isEqualTo(iat.plus(Duration.ofMinutes(15)));
        assertThat(token.expiresAt()).isEqualTo(jwt.getExpiresAt());
        assertThat(jwt.getHeaders()).containsEntry("alg", "HS256").containsEntry("typ", "JWT");
        assertThat(jwt.getClaims()).containsOnlyKeys("sub", "roles", "sid", "iss", "iat", "exp");
    }

    @Test
    void issue_noRoles_emptyRolesClaim() {
        JwtIssuer issuer = new JwtIssuer(encoder, Clock.fixed(NOW, ZoneOffset.UTC), properties(Duration.ofMinutes(15)));

        Jwt jwt = rawDecoder.decode(issuer.issue(7L, List.of(), UUID.randomUUID().toString()).value());

        assertThat(jwt.getClaimAsStringList("roles")).isEmpty();
    }

    @Test
    void accessToken_toString_masksTokenValue_NFR08() {
        JwtIssuer issuer = new JwtIssuer(encoder, Clock.fixed(NOW, ZoneOffset.UTC), properties(Duration.ofMinutes(15)));
        JwtIssuer.AccessToken token = issuer.issue(1L, Set.of(RoleCode.STUDENT), UUID.randomUUID().toString());

        assertThat(token.toString()).doesNotContain(token.value()).contains("****");
    }

    @Test
    void decode_tokenSignedWithOtherKey_rejected() {
        String otherSecret = Base64.getEncoder().encodeToString("ffffffffffffffffffffffffffffffff".getBytes());
        JwtEncoder otherEncoder = NimbusJwtEncoder.withSecretKey(SecurityConfig.secretKey(otherSecret))
                .algorithm(MacAlgorithm.HS256).build();
        JwtIssuer forger = new JwtIssuer(otherEncoder, Clock.fixed(NOW, ZoneOffset.UTC), properties(Duration.ofMinutes(15)));

        String forged = forger.issue(1L, Set.of(RoleCode.ADMIN), UUID.randomUUID().toString()).value();

        assertThatThrownBy(() -> rawDecoder.decode(forged)).isInstanceOf(BadJwtException.class);
    }

    @Test
    void secretKey_missingBlankInvalidOrShort_failsFastWithoutLeakingValue() {
        assertThatThrownBy(() -> SecurityConfig.secretKey(null)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not set");
        assertThatThrownBy(() -> SecurityConfig.secretKey("  ")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not set");
        assertThatThrownBy(() -> SecurityConfig.secretKey("not*base64!")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("base64").hasMessageNotContaining("not*base64!");
        String shortSecret = Base64.getEncoder().encodeToString(new byte[31]);
        assertThatThrownBy(() -> SecurityConfig.secretKey(shortSecret)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 bytes").hasMessageNotContaining(shortSecret);
    }

    @Test
    void secretKey_32Bytes_hmacSha256Key() {
        assertThat(SecurityConfig.secretKey(SECRET).getEncoded()).hasSize(32);
        assertThat(SecurityConfig.secretKey(SECRET).getAlgorithm()).isEqualTo("HmacSHA256");
    }

    private static EdumyProperties properties(Duration accessTtl) {
        return new EdumyProperties(
                new EdumyProperties.Security(new EdumyProperties.Jwt(accessTtl, Duration.ofDays(14), SECRET)),
                new EdumyProperties.Cors(List.of()),
                new EdumyProperties.Scheduler(false),
                new EdumyProperties.Payment("fake"),
                new EdumyProperties.Frontend("http://localhost:5173"),
                new EdumyProperties.Mail("no-reply@edumy.local"));
    }
}
