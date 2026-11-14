package com.edumy.learning.controller;

import static com.edumy.support.IdorAssertions.assertSameNotFound;
import static com.edumy.support.ProblemAssertions.expectProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.edumy.support.MySqlSeedSpringBootTest;
import com.edumy.support.SeedUserTokens;
import com.edumy.support.SqlStatementCounter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * {@code GET /me/certificates/{code}/pdf} (UC-S20, phase-1 stub, P07-learning) on the dev seed, read-only: ownership is
 * checked on real data, the body is the static sample PDF, nothing is written.
 * <p>Seed: certificate 1 = {@code EDU-7K2M-9QXA-4F8T} (enrollment 11, student 6), certificate 2 =
 * {@code EDU-P3RD-8VNC-2HWE} (enrollment 3, student 7).
 */
@MySqlSeedSpringBootTest
class CertificatePdfStubIT {

    static final String CODE_OF_6 = "EDU-7K2M-9QXA-4F8T";
    static final String CODE_OF_7 = "EDU-P3RD-8VNC-2HWE";
    static final String UNKNOWN = "EDU-ZZZZ-ZZZZ-ZZZZ";
    static final Pattern WRITE = Pattern.compile("^\\s*(insert|update|delete|replace|alter|truncate|create|drop)\\b",
            Pattern.CASE_INSENSITIVE);
    static final List<String> TABLES = List.of("certificates", "enrollments", "lesson_progresses", "users");

    @Autowired
    MockMvc mvc;
    @Autowired
    SeedUserTokens tokens;
    @Autowired
    SqlStatementCounter sql;
    @Autowired
    JdbcClient jdbc;

    private Map<String, Long> counts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        TABLES.forEach(t -> counts.put(t, jdbc.sql("select count(*) from " + t).query(Long.class).single()));
        return counts;
    }

    /** Performs the request as {@code userId}, then asserts that nothing was written (rows and statements). */
    private ResultActions pdf(long userId, String code) throws Exception {
        Map<String, Long> before = counts();
        sql.reset();
        ResultActions result = mvc.perform(request(userId, code));
        List<String> statements = sql.statements();
        assertThat(statements).as("no write statement: " + statements)
                .noneMatch(s -> WRITE.matcher(s).find() || s.toLowerCase().contains(" for update"));
        assertThat(counts()).as("row counts unchanged").isEqualTo(before);
        return result;
    }

    private MockHttpServletRequestBuilder request(long userId, String code) {
        return get("/api/v1/me/certificates/" + code + "/pdf").header(HttpHeaders.AUTHORIZATION, tokens.bearer(userId));
    }

    private void assertSamplePdf(long userId, String code) throws Exception {
        MvcResult result = pdf(userId, code)
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/pdf"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + code + ".pdf\""))
                .andExpect(header().string("X-Edumy-Stub", "true"))
                .andReturn();
        String text = new String(result.getResponse().getContentAsByteArray(), StandardCharsets.ISO_8859_1);
        assertThat(text).startsWith("%PDF-");
        assertThat(text.stripTrailing()).endsWith("%%EOF");
        assertThat(text).contains("SAMPLE CERTIFICATE");
    }

    @Test
    void ownCertificate_200_samplePdfWithHeaders_UCS20() throws Exception {
        assertSamplePdf(6, CODE_OF_6);
        assertSamplePdf(7, CODE_OF_7);
    }

    @Test
    void ownCertificate_oneStatement() throws Exception {
        pdf(6, CODE_OF_6).andExpect(status().isOk()); // warm-up (session / user-status caches)
        sql.reset();
        mvc.perform(request(6, CODE_OF_6)).andExpect(status().isOk());
        assertThat(sql.count()).as(sql.statements().toString()).isEqualTo(1);
    }

    @Test
    void anotherUsersCode_unknownCode_identical404_IDOR() throws Exception {
        assertSameNotFound(mvc, request(7, CODE_OF_6), request(7, UNKNOWN));
        assertSameNotFound(mvc, request(6, CODE_OF_7), request(6, UNKNOWN));
        // a user without certificates, admin and instructor
        assertSameNotFound(mvc, request(8, CODE_OF_6), request(8, UNKNOWN));
        assertSameNotFound(mvc, request(SeedUserTokens.ADMIN, CODE_OF_6), request(SeedUserTokens.ADMIN, UNKNOWN));
        assertSameNotFound(mvc, request(SeedUserTokens.INSTRUCTOR, CODE_OF_7),
                request(SeedUserTokens.INSTRUCTOR, UNKNOWN));
    }

    @Test
    void malformedCodes_sameNotFound_noWrites() throws Exception {
        List<String> malformed = List.of(
                CODE_OF_6.toLowerCase(),
                "EDU-7K2M-9QXA-4F8",
                "EDU-7K2M-9QXA-4F8TX",
                CODE_OF_6 + ".pdf",
                "EDU_7K2M_9QXA_4F8T",
                "X".repeat(300));
        for (String code : malformed) {
            assertSameNotFound(mvc, request(6, code), request(6, UNKNOWN));
        }
    }

    @Test
    void traversalAndEncodedValues_rejectedBeforeTheHandler_neverA200() throws Exception {
        // dot segments and percent-escapes are refused by the servlet / Spring Security firewall (400, empty body),
        // independent of the owner and the data; whatever else would reach the handler is the usual 404.
        for (String code : List.of("..", "%2e%2e", "..%2f..%2fetc%2fpasswd", CODE_OF_6 + "%20", CODE_OF_6 + "%0A",
                "%27%20OR%201%3D1--", CODE_OF_6 + "/..")) {
            MvcResult result = mvc.perform(request(6, code)).andReturn();
            assertThat(result.getResponse().getStatus()).as(code).isIn(400, 404);
            assertThat(result.getResponse().getContentAsString(StandardCharsets.ISO_8859_1)).as(code)
                    .doesNotContain("%PDF-");
        }
    }

    @Test
    void withoutToken_401() throws Exception {
        expectProblem(mvc.perform(get("/api/v1/me/certificates/" + CODE_OF_6 + "/pdf")), 401, "UNAUTHENTICATED");
    }

    @Test
    void lockedUserAndGarbageToken_401() throws Exception {
        expectProblem(mvc.perform(request(SeedUserTokens.LOCKED, CODE_OF_6)), 401, "UNAUTHENTICATED");
        expectProblem(mvc.perform(get("/api/v1/me/certificates/" + CODE_OF_6 + "/pdf")
                .header(HttpHeaders.AUTHORIZATION, "Bearer not.a.jwt")), 401, "UNAUTHENTICATED");
    }

    @Test
    void samplePdfIsNotServedAsPublicStaticResource() throws Exception {
        // static/ is mapped to /** by Spring Boot; SecurityConfig's anyRequest().authenticated() must keep it closed
        MvcResult result = mvc.perform(get("/sample/certificate-sample.pdf")).andReturn();
        assertThat(result.getResponse().getStatus()).isIn(401, 404);
        assertThat(result.getResponse().getContentAsString(StandardCharsets.ISO_8859_1)).doesNotStartWith("%PDF-");
    }
}
