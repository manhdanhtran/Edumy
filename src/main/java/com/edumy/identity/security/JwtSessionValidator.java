package com.edumy.identity.security;

import java.util.Optional;

import com.edumy.shared.security.AuthenticatedUser;
import com.edumy.shared.security.CurrentUser;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Makes revocation effective for live access tokens (ADR-002, docs/ai/04 §4.1): a token is accepted only while its
 * {@code sid} session still has a non-revoked refresh token and its user is {@code active}. Both answers come from
 * {@link SessionStatusCache}. Runs after the signature, timestamp and issuer checks (see {@link SecurityConfig}).
 */
@Component
public class JwtSessionValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2Error SESSION_ENDED =
            new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, "The session has ended", null);
    private static final OAuth2Error MALFORMED =
            new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, "Missing or invalid sub/sid claim", null);

    private final SessionStatusCache sessionStatus;

    public JwtSessionValidator(SessionStatusCache sessionStatus) {
        this.sessionStatus = sessionStatus;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        Optional<CurrentUser> user = AuthenticatedUser.from(jwt);
        if (user.isEmpty()) {
            return OAuth2TokenValidatorResult.failure(MALFORMED);
        }
        boolean valid = sessionStatus.isSessionActive(user.get().sessionId())
                && sessionStatus.isUserActive(user.get().id());
        return valid ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(SESSION_ENDED);
    }
}
