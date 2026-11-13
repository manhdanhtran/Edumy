package com.edumy.catalog.controller;

import static com.edumy.support.ProblemAssertions.expectProblem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.ThreadLocalRandom;

import com.edumy.support.IdorAssertions;
import com.edumy.support.MySqlSeedSpringBootTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * UC-G14 {@code GET /api/v1/catalog/certificates/{code}} on the dev seed: certificates EDU-7K2M-9QXA-4F8T (Đỗ Thị
 * Hương, course 2) and EDU-P3RD-8VNC-2HWE (Hoàng Minh Tuấn, course 5). Each test uses its own client IP so the shared
 * rate limiter (30/min per IP, NFR-03) never interferes.
 */
@MySqlSeedSpringBootTest
class CertificateVerificationControllerIT {

    static final String CERTIFICATES = "/api/v1/catalog/certificates/";

    @Autowired
    MockMvc mvc;

    private final String ip = "10.%d.%d.%d".formatted(ThreadLocalRandom.current().nextInt(256),
            ThreadLocalRandom.current().nextInt(256), ThreadLocalRandom.current().nextInt(1, 255));

    private MockHttpServletRequestBuilder verify(String code) {
        return get(CERTIFICATES + code).with(request -> {
            request.setRemoteAddr(ip);
            return request;
        });
    }

    @Test
    void verify_knownCode_anonymous_snapshotData_UCG14_BR40() throws Exception {
        mvc.perform(verify("EDU-7K2M-9QXA-4F8T"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.certificateCode").value("EDU-7K2M-9QXA-4F8T"))
                .andExpect(jsonPath("$.studentName").value("Đỗ Thị Hương"))
                .andExpect(jsonPath("$.courseTitle").value("HTML và CSS cho người mới bắt đầu"))
                .andExpect(jsonPath("$.instructorName").value("Nguyễn Văn Hùng"))
                .andExpect(jsonPath("$.issuedAt").value("2026-08-06T20:00:00Z"))
                // nothing else about the student (UC-G14 E1 / NFR-01)
                .andExpect(jsonPath("$.studentId").doesNotExist())
                .andExpect(jsonPath("$.email").doesNotExist());
        mvc.perform(verify("EDU-P3RD-8VNC-2HWE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.studentName").value("Hoàng Minh Tuấn"))
                .andExpect(jsonPath("$.instructorName").value("Lê Thị Mai"));
    }

    @Test
    void verify_unknownCode_404_UCG14E1() throws Exception {
        expectProblem(mvc.perform(verify("EDU-0000-0000-0000")), 404, "RESOURCE_NOT_FOUND");
        // a malformed code answers exactly like an unknown one
        IdorAssertions.assertSameNotFound(mvc, verify("not-a-code"), verify("EDU-0000-0000-0000"));
    }
}
