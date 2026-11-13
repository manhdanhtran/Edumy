package com.edumy.identity.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

import com.edumy.shared.config.EdumyProperties;
import com.edumy.shared.security.AuthenticatedUser;
import com.edumy.shared.security.RoleCode;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

/**
 * Issues access JWTs (HS256, docs/ai/04 §4.1, ADR-002): {@code sub} = user id, {@code roles} = active role codes
 * (lower-case), {@code sid} = session id, {@code iat}, {@code exp} = iat + {@code edumy.security.jwt.access-ttl},
 * {@code iss} = {@value #ISSUER}. No email, name or other PII. Times come from the injected {@link Clock}.
 */
@Component
public class JwtIssuer {

    public static final String ISSUER = "edumy";

    private final JwtEncoder encoder;
    private final Clock clock;
    private final Duration accessTtl;

    public JwtIssuer(JwtEncoder encoder, Clock clock, EdumyProperties properties) {
        this.encoder = encoder;
        this.clock = clock;
        this.accessTtl = properties.security().jwt().accessTtl();
    }

    /** Access token of session {@code sid} for {@code userId} holding {@code roles} (active roles at this moment). */
    public AccessToken issue(Long userId, Collection<RoleCode> roles, String sid) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(sid, "sid");
        // JWT NumericDate has second precision: truncate so the returned expiresAt equals the exp claim
        Instant issuedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = issuedAt.plus(accessTtl);
        List<String> roleCodes = (roles.isEmpty() ? EnumSet.noneOf(RoleCode.class) : EnumSet.copyOf(roles))
                .stream().map(RoleCode::code).toList();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(userId.toString())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim(AuthenticatedUser.ROLES_CLAIM, roleCodes)
                .claim(AuthenticatedUser.SESSION_ID_CLAIM, sid)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
        String value = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new AccessToken(value, expiresAt);
    }

    /** A signed access token and its expiry. {@link #toString()} never shows the token (NFR-08). */
    public record AccessToken(String value, Instant expiresAt) {

        @Override
        public String toString() {
            return "AccessToken[value=****, expiresAt=" + expiresAt + "]";
        }
    }
}
