package com.edumy.identity.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.edumy.shared.config.EdumyProperties;
import com.edumy.shared.security.RoleCode;
import com.edumy.shared.web.TraceIdFilter;
import com.edumy.support.JwtTestTokens;
import com.edumy.support.JwtTestTokens.Session;
import com.edumy.support.MySqlSpringBootTest;
import com.edumy.support.TestUsers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * SecurityConfig + TraceIdFilter + JWT checks through the real filter chain (ADR-002, docs/ai/04 §4.1/§4.3). No
 * controller exists yet: a protected path without a handler gets 401/403 before MVC, and an authenticated request to
 * it reaches MVC and gets the no-handler 404 ({@code RESOURCE_NOT_FOUND}), which proves the token was accepted.
 */
@MySqlSpringBootTest
class JwtSecurityIT {

    static final String PROTECTED = "/api/v1/me/nothing-here";
    static final String ADMIN = "/api/v1/admin/nothing-here";

    @Autowired
    MockMvc mvc;
    @Autowired
    JwtTestTokens tokens;
    @Autowired
    TestUsers users;
    @Autowired
    JwtEncoder jwtEncoder;
    @Autowired
    JwtDecoder jwtDecoder;
    @Autowired
    Clock clock;
    @Autowired
    EdumyProperties properties;
    @Autowired
    SessionStatusCache sessionStatus;

    @AfterEach
    void cleanUp() {
        users.deleteAll();
    }

    // ------------------------------------------------------------------------------------------------- 401 / trace id

    @Test
    void protectedPath_noToken_401ProblemDetailWithTraceIdOfHeader() throws Exception {
        MvcResult result = mvc.perform(get(PROTECTED))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(jsonPath("$.type").value("https://edumy.local/problems/unauthenticated"))
                .andExpect(jsonPath("$.title").value("Chưa đăng nhập"))
                .andExpect(jsonPath("$.detail").value("Vui lòng đăng nhập để tiếp tục."))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.instance").value(PROTECTED))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andReturn();
        String traceId = result.getResponse().getHeader(TraceIdFilter.HEADER);
        assertThat(traceId).matches("[0-9a-f]{32}");
        jsonPath("$.traceId").value(traceId).match(result);
    }

    @Test
    void protectedPath_validIncomingTraceId_echoedAndUsedInBody() throws Exception {
        mvc.perform(get(PROTECTED).header(TraceIdFilter.HEADER, "spa-7f3a-0001"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(TraceIdFilter.HEADER, "spa-7f3a-0001"))
                .andExpect(jsonPath("$.traceId").value("spa-7f3a-0001"));
    }

    @Test
    void protectedPath_invalidIncomingTraceId_replaced() throws Exception {
        MvcResult result = mvc.perform(get(PROTECTED).header(TraceIdFilter.HEADER, "bad id <script>"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(TraceIdFilter.HEADER, matchesPattern("[0-9a-f]{32}")))
                .andReturn();
        jsonPath("$.traceId").value(result.getResponse().getHeader(TraceIdFilter.HEADER)).match(result);
    }

    @Test
    void authenticatedNoHandler_404ProblemDetailWithTraceIdOfHeader() throws Exception {
        Session session = tokens.newUserSession(RoleCode.STUDENT);

        MvcResult result = mvc.perform(get(PROTECTED).header(HttpHeaders.AUTHORIZATION, session.bearer()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andReturn();
        jsonPath("$.traceId").value(result.getResponse().getHeader(TraceIdFilter.HEADER)).match(result);
    }

    // ------------------------------------------------------------------------------------------------------ 403 admin

    @Test
    void adminPath_studentToken_403ProblemDetailSameShape() throws Exception {
        Session student = tokens.newUserSession(RoleCode.STUDENT, RoleCode.INSTRUCTOR);

        MvcResult result = mvc.perform(get(ADMIN).header(HttpHeaders.AUTHORIZATION, student.bearer()))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://edumy.local/problems/access-denied"))
                .andExpect(jsonPath("$.title").value("Không có quyền"))
                .andExpect(jsonPath("$.detail").value("Bạn không có quyền thực hiện thao tác này."))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.instance").value(ADMIN))
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
                .andReturn();
        String traceId = result.getResponse().getHeader(TraceIdFilter.HEADER);
        assertThat(traceId).matches("[0-9a-f]{32}");
        jsonPath("$.traceId").value(traceId).match(result);
    }

    @Test
    void adminPath_adminToken_passesSecurity() throws Exception {
        Session admin = tokens.newUserSession(RoleCode.STUDENT, RoleCode.ADMIN);

        mvc.perform(get(ADMIN).header(HttpHeaders.AUTHORIZATION, admin.bearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void adminPath_noToken_401NotForbidden() throws Exception {
        mvc.perform(get(ADMIN))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    // ---------------------------------------------------------------------------------------------------- route rules

    @Test
    void publicPaths_noToken_notRejectedBySecurity() throws Exception {
        for (String path : new String[] {"/api/v1/catalog/nothing-here", "/api/v1/webhooks/nothing-here"}) {
            mvc.perform(get(path))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        }
        mvc.perform(post("/api/v1/auth/login")) // reaches AuthController: no body → 4xx MALFORMED_REQUEST
                .andExpect(status().is(not(401)))
                .andExpect(status().is(not(403)))
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        mvc.perform(post("/api/v1/auth/email-verification/confirm"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/actuator/health"))
                .andExpect(status().is(not(401)))
                .andExpect(status().is(not(403)));
    }

    @Test
    void logoutAndResend_noToken_401_Q2() throws Exception {
        mvc.perform(post("/api/v1/auth/logout"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(post("/api/v1/auth/email-verification/resend"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void actuatorOtherThanHealthInfo_noToken_401() throws Exception {
        mvc.perform(get("/actuator/caches")).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------------------------------------- invalid tokens

    @Test
    void protectedPath_expiredToken_401() throws Exception {
        Session session = tokens.newUserSession(RoleCode.STUDENT);
        Duration ttl = properties.security().jwt().accessTtl();
        // issued long enough ago that exp is past even with the 60 s clock skew
        JwtIssuer pastIssuer = new JwtIssuer(jwtEncoder, Clock.offset(clock, ttl.plusMinutes(5).negated()), properties);
        String expired = pastIssuer.issue(session.userId(), Set.of(RoleCode.STUDENT), session.sid()).value();

        expectUnauthenticated(expired);
    }

    @Test
    void protectedPath_badSignature_401() throws Exception {
        Session session = tokens.newUserSession(RoleCode.STUDENT);
        byte[] otherKey = new byte[32];
        new SecureRandom().nextBytes(otherKey);
        JwtEncoder forgedEncoder = NimbusJwtEncoder.withSecretKey(
                SecurityConfig.secretKey(Base64.getEncoder().encodeToString(otherKey))).build();
        String forged = new JwtIssuer(forgedEncoder, clock, properties)
                .issue(session.userId(), Set.of(RoleCode.ADMIN), session.sid()).value();

        expectUnauthenticated(forged);
    }

    @Test
    void protectedPath_wrongIssuer_401() throws Exception {
        Session session = tokens.newUserSession(RoleCode.STUDENT);
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("someone-else")
                .subject(session.userId().toString())
                .issuedAt(now)
                .expiresAt(now.plus(Duration.ofMinutes(15)))
                .claim("roles", List.of("student"))
                .claim("sid", session.sid())
                .build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();

        expectUnauthenticated(token);
    }

    @Test
    void protectedPath_garbageToken_401() throws Exception {
        expectUnauthenticated("not.a.jwt");
    }

    @Test
    void protectedPath_unknownSession_401() throws Exception {
        Session session = tokens.newUserSession(RoleCode.STUDENT);
        JwtIssuer issuer = new JwtIssuer(jwtEncoder, clock, properties);
        String noSuchSession = issuer.issue(session.userId(), Set.of(RoleCode.STUDENT), UUID.randomUUID().toString())
                .value();

        expectUnauthenticated(noSuchSession);
    }

    // ---------------------------------------------------------------------------------- revocation (ADR-002, D-05)

    @Test
    void protectedPath_sessionRevoked_401Immediately() throws Exception {
        Session session = tokens.newUserSession(RoleCode.STUDENT);
        expectAccepted(session); // caches "session active"

        tokens.revokeInDatabase(session.sid());
        sessionStatus.evictSession(session.sid()); // what every revoke does (no transaction here → immediate)

        expectUnauthenticated(session.accessToken());
    }

    @Test
    void protectedPath_otherSessionOfSameUserRevoked_stillAccepted() throws Exception {
        Session first = tokens.newUserSession(RoleCode.STUDENT);
        Session second = tokens.session(first.user());

        tokens.revokeInDatabase(first.sid());
        sessionStatus.evictSession(first.sid());

        expectUnauthenticated(first.accessToken());
        expectAccepted(second);
    }

    @Test
    void protectedPath_userLocked_401Immediately_D05() throws Exception {
        Session session = tokens.newUserSession(RoleCode.STUDENT);
        expectAccepted(session); // caches "user active"

        users.lock(session.userId());
        sessionStatus.evictUser(session.userId());

        expectUnauthenticated(session.accessToken());
    }

    // ----------------------------------------------------------------------------------------- issue/decode round trip

    @Test
    void jwtIssuer_issueThenDecode_roundTripWithClaims() {
        Session session = tokens.newUserSession(RoleCode.STUDENT, RoleCode.INSTRUCTOR);
        JwtIssuer.AccessToken token = new JwtIssuer(jwtEncoder, clock, properties)
                .issue(session.userId(), session.user().roles(), session.sid());

        Jwt jwt = jwtDecoder.decode(token.value());

        assertThat(jwt.getSubject()).isEqualTo(session.userId().toString());
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("student", "instructor");
        assertThat(jwt.getClaimAsString("sid")).isEqualTo(session.sid());
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("edumy");
        assertThat(jwt.getExpiresAt()).isEqualTo(jwt.getIssuedAt().plus(properties.security().jwt().accessTtl()))
                .isEqualTo(token.expiresAt());
        assertThat(jwt.getClaims()).doesNotContainKeys("email", "name", "fullName");
    }

    private void expectAccepted(Session session) throws Exception {
        mvc.perform(get(PROTECTED).header(HttpHeaders.AUTHORIZATION, session.bearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    private void expectUnauthenticated(String token) throws Exception {
        MvcResult result = mvc.perform(get(PROTECTED).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.title").value("Chưa đăng nhập"))
                .andExpect(jsonPath("$.instance").value(PROTECTED))
                .andReturn();
        jsonPath("$.traceId").value(result.getResponse().getHeader(TraceIdFilter.HEADER)).match(result);
        assertThat(result.getResponse().getContentAsString()).doesNotContain(token);
    }
}
