package com.edumy.learning.controller;

import static com.edumy.support.ProblemAssertions.expectProblem;
import static com.edumy.support.ProblemAssertions.withoutTraceAndInstance;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;

import com.edumy.shared.security.RoleCode;
import com.edumy.support.CourseFixtures;
import com.edumy.support.JwtTestTokens;
import com.edumy.support.JwtTestTokens.Session;
import com.edumy.support.MySqlSpringBootTest;
import com.edumy.support.TestUsers;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * UC-S20 {@code GET /me/certificates/{code}/pdf} on MySQL (P12): the owner gets a real PDF of the stored snapshot (BR-40;
 * later renames do not change it) with Vietnamese text, code and verification link; headers (application/pdf, attachment
 * with a safe file name, no-store, no stub marker); another student's, unknown and malformed codes give the same 404
 * (NFR-01); no token → 401.
 */
@MySqlSpringBootTest
class CertificatePdfIT {

    static final String CODE = "EDU-PDF1-TEST-ANH9";

    @Autowired
    MockMvc mvc;
    @Autowired
    TestUsers users;
    @Autowired
    JwtTestTokens tokens;
    @Autowired
    JdbcClient jdbc;

    CourseFixtures courses;
    Session student;
    Session other;

    @BeforeEach
    void setUp() {
        courses = new CourseFixtures(jdbc);
        Session instructor = tokens.newUserSession(RoleCode.STUDENT, RoleCode.INSTRUCTOR);
        student = tokens.newUserSession(RoleCode.STUDENT);
        other = tokens.newUserSession(RoleCode.STUDENT);
        long course = courses.draftCourse(instructor.userId());
        courses.setStatus(course, "published", false);
        long enrollment = courses.enrollment(student.userId(), course);
        jdbc.sql("""
                        INSERT INTO certificates (enrollment_id, certificate_code, student_name, course_title,
                                                  instructor_name, issued_at)
                        VALUES (?, ?, 'Nguyễn Thị Ánh', 'Thiết kế giao diện với Figma', 'Lê Văn Đạt', ?)""")
                .params(enrollment, CODE, Timestamp.from(Instant.parse("2026-11-10T02:00:00Z"))).update();
        // renamed afterwards: the certificate keeps its snapshot (BR-40)
        jdbc.sql("UPDATE users SET full_name = 'Tên Mới' WHERE id = ?").param(student.userId()).update();
    }

    @AfterEach
    void cleanUp() {
        courses.deleteAll();
        users.deleteAll();
    }

    MvcResult pdf(Session who, String code) throws Exception {
        return mvc.perform(get("/api/v1/me/certificates/" + code + "/pdf").header(HttpHeaders.AUTHORIZATION,
                who.bearer())).andReturn();
    }

    @Test
    void owner_getsThePdfOfTheSnapshot_vietnameseCodeAndLink_UCS20_BR40() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/me/certificates/" + CODE + "/pdf")
                        .header(HttpHeaders.AUTHORIZATION, student.bearer()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/pdf"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + CODE + ".pdf\""))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().doesNotExist("X-Edumy-Stub"))
                .andReturn();

        PdfReader reader = new PdfReader(result.getResponse().getContentAsByteArray());
        String text;
        try {
            text = new PdfTextExtractor(reader).getTextFromPage(1);
        } finally {
            reader.close();
        }
        assertThat(text).contains("Nguyễn Thị Ánh").doesNotContain("Tên Mới")
                .contains("Thiết kế giao diện với Figma").contains("Lê Văn Đạt").contains("Ngày cấp: 10/11/2026")
                .contains(CODE).contains("http://localhost:5173/certificates/" + CODE);
    }

    @Test
    void anotherStudentsUnknownOrMalformedCode_identical404_NFR01() throws Exception {
        MvcResult foreign = expectProblem(mvc.perform(get("/api/v1/me/certificates/" + CODE + "/pdf")
                .header(HttpHeaders.AUTHORIZATION, other.bearer())), 404, "RESOURCE_NOT_FOUND");
        MvcResult unknown = expectProblem(mvc.perform(get("/api/v1/me/certificates/EDU-ZZZZ-ZZZZ-ZZZZ/pdf")
                .header(HttpHeaders.AUTHORIZATION, other.bearer())), 404, "RESOURCE_NOT_FOUND");
        MvcResult malformed = expectProblem(mvc.perform(get("/api/v1/me/certificates/edu-pdf1-test-anh9/pdf")
                .header(HttpHeaders.AUTHORIZATION, student.bearer())), 404, "RESOURCE_NOT_FOUND");

        assertThat(withoutTraceAndInstance(foreign)).isEqualTo(withoutTraceAndInstance(unknown))
                .isEqualTo(withoutTraceAndInstance(malformed));
    }

    @Test
    void noToken_401() throws Exception {
        expectProblem(mvc.perform(get("/api/v1/me/certificates/" + CODE + "/pdf")), 401, "UNAUTHENTICATED");
    }
}
